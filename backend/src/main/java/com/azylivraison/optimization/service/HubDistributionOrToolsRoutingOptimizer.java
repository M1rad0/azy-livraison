package com.azylivraison.optimization.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.azylivraison.optimization.config.OptimizationProperties;
import com.azylivraison.optimization.domain.OperationalPolicy;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.IntVar;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;

import org.springframework.stereotype.Component;

/** Routes already redistributed packages from the common hub to their recipients. */
@Component
class HubDistributionOrToolsRoutingOptimizer {

    private static final long PROHIBITIVE_ARC_COST = 1_000_000_000_000L;
    private static final long MAX_REGULAR_COST = PROHIBITIVE_ARC_COST / 4;
    private static final long LATENESS_COST_PER_SECOND = 10_000L;

    private final OptimizationProperties properties;

    HubDistributionOrToolsRoutingOptimizer(OptimizationProperties properties) {
        Loader.loadNativeLibraries();
        this.properties = properties;
    }

    HubDistributionSolvedOptimization solve(HubDistributionProblem problem) {
        return solve(problem, properties.operationalPolicy(), properties.timeLimit());
    }

    HubDistributionSolvedOptimization solve(
            HubDistributionProblem problem,
            OperationalPolicy policy,
            Duration timeLimit) {
        long startedAt = System.nanoTime();
        int actualNodeCount = problem.distancesMeters().size();
        int vehicleCount = problem.couriers().size();
        int[] starts = new int[vehicleCount];
        Arrays.fill(starts, problem.hubNode());
        int[] ends = new int[vehicleCount];
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            ends[vehicle] = actualNodeCount + vehicle;
        }

        RoutingIndexManager manager = new RoutingIndexManager(
                actualNodeCount + vehicleCount,
                vehicleCount,
                starts,
                ends);
        RoutingModel routing = new RoutingModel(manager);
        long maximumDistance = maximumFiniteDistance(problem.distancesMeters());

        Map<Integer, Integer> jobByNode = jobIndexByNode(problem);
        int distanceCallback = routing.registerTransitCallback((fromIndex, toIndex) -> distanceCost(
                problem,
                actualNodeCount,
                manager.indexToNode(fromIndex),
                manager.indexToNode(toIndex)));
        routing.setArcCostEvaluatorOfAllVehicles(distanceCallback);
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            routing.setFixedCostOfVehicle(problem.vehicleActivationCostMeters()[vehicle], vehicle);
        }

        int demandCallback = routing.registerUnaryTransitCallback(fromIndex -> {
            Integer jobIndex = jobByNode.get(manager.indexToNode(fromIndex));
            return jobIndex == null ? 0 : grams(problem.jobs().get(jobIndex).deliveryPackage().weightKg());
        });
        long[] capacities = problem.couriers().stream()
                .mapToLong(courier -> grams(courier.maxWeightKg()))
                .toArray();
        routing.addDimensionWithVehicleCapacity(demandCallback, 0, capacities, true, "Capacity");

        int maximumUsedCourierCount = maximumFeasibleCourierCount(problem, policy);
        IntVar[] activeVehicles = new IntVar[vehicleCount];
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            activeVehicles[vehicle] = routing.activeVehicleVar(vehicle);
        }
        routing.solver().addConstraint(routing.solver().makeSumEquality(
                activeVehicles,
                maximumUsedCourierCount));

        int workloadCallback = routing.registerUnaryTransitCallback(fromIndex ->
                jobByNode.containsKey(manager.indexToNode(fromIndex)) ? 1 : 0);
        routing.addDimension(
                workloadCallback,
                0,
                Math.max(1, problem.jobs().size()),
                true,
                "Workload");
        RoutingDimension workloadDimension = routing.getMutableDimension("Workload");
        long workloadBalanceCoefficient = workloadBalanceCoefficient(
                maximumDistance,
                problem.jobs().size(),
                vehicleCount,
                Arrays.stream(problem.vehicleActivationCostMeters()).max().orElse(0));
        workloadDimension.setGlobalSpanCostCoefficient(workloadBalanceCoefficient);
        if (maximumUsedCourierCount > 0) {
            long fairShareFloor = problem.jobs().size() / maximumUsedCourierCount;
            for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
                workloadDimension.setCumulVarSoftUpperBound(
                        routing.end(vehicle),
                        fairShareFloor,
                        workloadBalanceCoefficient);
            }
        }

        long deliveryServiceSeconds = policy.deliveryServiceDuration().toSeconds();
        OffsetDateTime reference = timeReference(problem);
        long horizon = timeHorizon(problem, reference, maximumDistance, policy);
        int[] timeCallbacks = new int[vehicleCount];
        long[] timeCapacities = new long[vehicleCount];
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            Courier courier = problem.couriers().get(vehicle);
            timeCallbacks[vehicle] = routing.registerTransitCallback((fromIndex, toIndex) -> {
                int fromNode = manager.indexToNode(fromIndex);
                int toNode = manager.indexToNode(toIndex);
                long travel = travelSeconds(distanceCost(problem, actualNodeCount, fromNode, toNode), courier);
                long service = jobByNode.containsKey(fromNode) ? deliveryServiceSeconds : 0;
                return saturatedAdd(travel, service);
            });
            timeCapacities[vehicle] = horizon;
        }
        routing.addDimensionWithVehicleTransitAndCapacity(
                timeCallbacks,
                horizon,
                timeCapacities,
                false,
                "Time");
        RoutingDimension timeDimension = routing.getMutableDimension("Time");
        long commonDeparture = secondsBetween(reference, problem.distributionDepartureAt());
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            timeDimension.cumulVar(routing.start(vehicle)).setRange(commonDeparture, commonDeparture);
            routing.addVariableMinimizedByFinalizer(timeDimension.cumulVar(routing.end(vehicle)));
        }

        long dropPenalty = safeDropPenalty(
                maximumDistance,
                problem.jobs().size(),
                vehicleCount,
                Arrays.stream(problem.vehicleActivationCostMeters()).max().orElse(0),
                workloadBalanceCoefficient,
                policy);
        for (int jobIndex = 0; jobIndex < problem.jobs().size(); jobIndex++) {
            long routingIndex = manager.nodeToIndex(problem.deliveryNodes()[jobIndex]);
            DeliveryPackage deliveryPackage = problem.jobs().get(jobIndex).deliveryPackage();
            if (deliveryPackage.deliveryWindow() != null) {
                long requestedStart = secondsBetween(reference, deliveryPackage.deliveryWindow().startAt());
                long requestedEnd = secondsBetween(reference, deliveryPackage.deliveryWindow().endAt());
                long hardStart = Math.max(0, requestedStart - policy.deliveryWindowEarlyTolerance().toSeconds());
                long hardEnd = saturatedAdd(requestedEnd, policy.deliveryWindowLateTolerance().toSeconds());
                timeDimension.cumulVar(routingIndex).setRange(hardStart, Math.min(horizon, hardEnd));
                timeDimension.setCumulVarSoftUpperBound(routingIndex, requestedEnd, LATENESS_COST_PER_SECOND);
            }
            routing.addDisjunction(new long[]{routingIndex}, dropPenalty);
        }

        RoutingSearchParameters parameters = main.defaultRoutingSearchParameters()
                .toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PARALLEL_CHEAPEST_INSERTION)
                .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(protobufDuration(timeLimit))
                .build();
        Assignment solution = routing.solveWithParameters(parameters);
        long computationMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        if (solution == null) {
            Set<Integer> dropped = new HashSet<>();
            for (int jobIndex = 0; jobIndex < problem.jobs().size(); jobIndex++) dropped.add(jobIndex);
            return new HubDistributionSolvedOptimization(
                    routing.status().name(), computationMillis, reference, List.of(), dropped);
        }

        return new HubDistributionSolvedOptimization(
                routing.status().name(),
                computationMillis,
                reference,
                extractRoutes(problem, actualNodeCount, manager, routing, timeDimension, solution, jobByNode, policy),
                droppedJobs(problem, manager, routing, solution));
    }

    private List<HubDistributionSolvedOptimization.SolvedRoute> extractRoutes(
            HubDistributionProblem problem,
            int actualNodeCount,
            RoutingIndexManager manager,
            RoutingModel routing,
            RoutingDimension timeDimension,
            Assignment solution,
            Map<Integer, Integer> jobByNode,
            OperationalPolicy policy) {
        List<HubDistributionSolvedOptimization.SolvedRoute> routes = new ArrayList<>();
        long departure = secondsBetween(timeReference(problem), problem.distributionDepartureAt());
        long serviceSeconds = policy.deliveryServiceDuration().toSeconds();
        for (int vehicle = 0; vehicle < problem.couriers().size(); vehicle++) {
            long index = routing.start(vehicle);
            long previousServiceCompleted = departure;
            double cumulativeDistance = 0;
            long driving = 0;
            long waiting = 0;
            List<HubDistributionSolvedOptimization.SolvedVisit> visits = new ArrayList<>();
            while (!routing.isEnd(index)) {
                int fromNode = manager.indexToNode(index);
                long nextIndex = solution.value(routing.nextVar(index));
                int toNode = manager.indexToNode(nextIndex);
                double arcDistance = rawDistance(problem, actualNodeCount, fromNode, toNode);
                long travel = Math.round(travelSeconds(arcDistance, problem.couriers().get(vehicle)));
                cumulativeDistance += arcDistance;
                driving = saturatedAdd(driving, travel);
                Integer jobIndex = jobByNode.get(toNode);
                if (jobIndex != null) {
                    long serviceStarted = solution.min(timeDimension.cumulVar(nextIndex));
                    long physicalArrival = saturatedAdd(previousServiceCompleted, travel);
                    long stopWaiting = Math.max(0, serviceStarted - physicalArrival);
                    waiting = saturatedAdd(waiting, stopWaiting);
                    long completed = saturatedAdd(serviceStarted, serviceSeconds);
                    long lateness = latenessSeconds(problem.jobs().get(jobIndex), timeReference(problem), serviceStarted);
                    visits.add(new HubDistributionSolvedOptimization.SolvedVisit(
                            jobIndex,
                            physicalArrival,
                            serviceStarted,
                            completed,
                            stopWaiting,
                            lateness,
                            arcDistance,
                            travel,
                            cumulativeDistance));
                    previousServiceCompleted = completed;
                }
                index = nextIndex;
            }
            if (!visits.isEmpty()) {
                routes.add(new HubDistributionSolvedOptimization.SolvedRoute(
                        vehicle,
                        departure,
                        solution.min(timeDimension.cumulVar(index)),
                        cumulativeDistance,
                        driving,
                        waiting,
                        serviceSeconds * visits.size(),
                        visits));
            }
        }
        return List.copyOf(routes);
    }

    private long latenessSeconds(MultiClientOptimizationJob job, OffsetDateTime reference, long serviceStarted) {
        if (job.deliveryPackage().deliveryWindow() == null) return 0;
        long requestedEnd = secondsBetween(reference, job.deliveryPackage().deliveryWindow().endAt());
        return Math.max(0, serviceStarted - requestedEnd);
    }

    private Set<Integer> droppedJobs(
            HubDistributionProblem problem,
            RoutingIndexManager manager,
            RoutingModel routing,
            Assignment solution) {
        Set<Integer> dropped = new HashSet<>();
        for (int jobIndex = 0; jobIndex < problem.jobs().size(); jobIndex++) {
            long routingIndex = manager.nodeToIndex(problem.deliveryNodes()[jobIndex]);
            if (solution.value(routing.nextVar(routingIndex)) == routingIndex) dropped.add(jobIndex);
        }
        return dropped;
    }

    private Map<Integer, Integer> jobIndexByNode(HubDistributionProblem problem) {
        Map<Integer, Integer> indexes = new HashMap<>();
        for (int jobIndex = 0; jobIndex < problem.deliveryNodes().length; jobIndex++) {
            indexes.put(problem.deliveryNodes()[jobIndex], jobIndex);
        }
        return Map.copyOf(indexes);
    }

    private OffsetDateTime timeReference(HubDistributionProblem problem) {
        List<OffsetDateTime> dates = new ArrayList<>();
        dates.add(problem.distributionDepartureAt());
        problem.jobs().stream()
                .map(MultiClientOptimizationJob::deliveryPackage)
                .filter(item -> item.deliveryWindow() != null)
                .forEach(item -> dates.add(item.deliveryWindow().startAt()));
        return dates.stream().min(Comparator.comparing(OffsetDateTime::toInstant))
                .orElse(problem.distributionDepartureAt());
    }

    private long timeHorizon(
            HubDistributionProblem problem,
            OffsetDateTime reference,
            long maximumDistance,
            OperationalPolicy policy) {
        long latest = secondsBetween(reference, problem.distributionDepartureAt());
        for (MultiClientOptimizationJob job : problem.jobs()) {
            if (job.deliveryPackage().deliveryWindow() != null) {
                latest = Math.max(latest, saturatedAdd(
                        secondsBetween(reference, job.deliveryPackage().deliveryWindow().endAt()),
                        policy.deliveryWindowLateTolerance().toSeconds()));
            }
        }
        double slowestSpeed = problem.couriers().stream()
                .map(Courier::averageSpeedKmh)
                .mapToDouble(BigDecimal::doubleValue)
                .min()
                .orElse(1);
        long travelAllowance = (long) Math.ceil(
                maximumDistance * Math.max(1, problem.jobs().size() + 1L) * 3.6 / slowestSpeed);
        long serviceAllowance = policy.deliveryServiceDuration().toSeconds() * Math.max(1, problem.jobs().size());
        return Math.max(86_400, saturatedAdd(latest, saturatedAdd(travelAllowance, serviceAllowance + 86_400)));
    }

    private long distanceCost(HubDistributionProblem problem, int actualNodeCount, int fromNode, int toNode) {
        if (toNode >= actualNodeCount || fromNode >= actualNodeCount) return 0;
        Double distance = problem.distancesMeters().get(fromNode).get(toNode);
        return distance == null ? PROHIBITIVE_ARC_COST : Math.max(0, Math.round(distance));
    }

    private double rawDistance(HubDistributionProblem problem, int actualNodeCount, int fromNode, int toNode) {
        if (toNode >= actualNodeCount || fromNode >= actualNodeCount) return 0;
        Double distance = problem.distancesMeters().get(fromNode).get(toNode);
        return distance == null ? 0 : Math.max(0, distance);
    }

    private long safeDropPenalty(
            long maximumDistance,
            int jobCount,
            int vehicleCount,
            long maximumActivationCost,
            long workloadBalanceCoefficient,
            OperationalPolicy policy) {
        long routeUpperBound = saturatedMultiply(
                saturatedAdd(maximumDistance, maximumActivationCost),
                Math.max(2, jobCount + vehicleCount + 1L));
        long workloadUpperBound = saturatedMultiply(
                workloadBalanceCoefficient,
                Math.max(1, jobCount * 2L));
        long latenessUpperBound = saturatedMultiply(
                saturatedMultiply(policy.deliveryWindowLateTolerance().toSeconds(), LATENESS_COST_PER_SECOND),
                Math.max(1, jobCount));
        return Math.min(
                PROHIBITIVE_ARC_COST / 2,
                saturatedAdd(
                        1_000_000,
                        saturatedAdd(routeUpperBound, saturatedAdd(workloadUpperBound, latenessUpperBound))));
    }

    private int maximumFeasibleCourierCount(HubDistributionProblem problem, OperationalPolicy policy) {
        int[] courierByJob = new int[problem.jobs().size()];
        Arrays.fill(courierByJob, -1);
        int matchedCouriers = 0;
        for (int courierIndex = 0; courierIndex < problem.couriers().size(); courierIndex++) {
            boolean[] visitedJobs = new boolean[problem.jobs().size()];
            if (matchCourier(courierIndex, problem, policy, courierByJob, visitedJobs)) {
                matchedCouriers++;
            }
        }
        return matchedCouriers;
    }

    private boolean matchCourier(
            int courierIndex,
            HubDistributionProblem problem,
            OperationalPolicy policy,
            int[] courierByJob,
            boolean[] visitedJobs) {
        for (int jobIndex = 0; jobIndex < problem.jobs().size(); jobIndex++) {
            if (visitedJobs[jobIndex] || !canServeDirectly(courierIndex, jobIndex, problem, policy)) {
                continue;
            }
            visitedJobs[jobIndex] = true;
            int currentCourier = courierByJob[jobIndex];
            if (currentCourier < 0
                    || matchCourier(currentCourier, problem, policy, courierByJob, visitedJobs)) {
                courierByJob[jobIndex] = courierIndex;
                return true;
            }
        }
        return false;
    }

    private boolean canServeDirectly(
            int courierIndex,
            int jobIndex,
            HubDistributionProblem problem,
            OperationalPolicy policy) {
        Courier courier = problem.couriers().get(courierIndex);
        DeliveryPackage deliveryPackage = problem.jobs().get(jobIndex).deliveryPackage();
        if (courier.maxWeightKg().compareTo(deliveryPackage.weightKg()) < 0) {
            return false;
        }
        Double distance = problem.distancesMeters()
                .get(problem.hubNode())
                .get(problem.deliveryNodes()[jobIndex]);
        if (distance == null || !Double.isFinite(distance) || distance < 0) {
            return false;
        }
        if (deliveryPackage.deliveryWindow() == null) {
            return true;
        }
        OffsetDateTime directArrival = problem.distributionDepartureAt()
                .plusSeconds(Math.round(travelSeconds(distance, courier)));
        OffsetDateTime hardEnd = deliveryPackage.deliveryWindow().endAt()
                .plus(policy.deliveryWindowLateTolerance());
        return !directArrival.toInstant().isAfter(hardEnd.toInstant());
    }

    private long workloadBalanceCoefficient(
            long maximumDistance,
            int jobCount,
            int vehicleCount,
            long maximumActivationCost) {
        long routeUpperBound = saturatedAdd(
                saturatedMultiply(maximumDistance, Math.max(1, jobCount)),
                saturatedMultiply(maximumActivationCost, Math.max(1, vehicleCount)));
        long maximumBalanceScore = Math.max(1, jobCount * 2L);
        long maximumSafeCoefficient = Math.max(1, MAX_REGULAR_COST / maximumBalanceScore);
        return Math.max(1, Math.min(maximumSafeCoefficient, saturatedAdd(routeUpperBound, 1)));
    }

    private long maximumFiniteDistance(List<List<Double>> distances) {
        return distances.stream()
                .flatMap(List::stream)
                .filter(java.util.Objects::nonNull)
                .mapToLong(value -> Math.max(0, Math.round(value)))
                .max()
                .orElse(1);
    }

    private long travelSeconds(long distanceMeters, Courier courier) {
        if (distanceMeters >= PROHIBITIVE_ARC_COST) return PROHIBITIVE_ARC_COST;
        return Math.round(travelSeconds((double) distanceMeters, courier));
    }

    private double travelSeconds(double distanceMeters, Courier courier) {
        return distanceMeters * 3.6 / courier.averageSpeedKmh().doubleValue();
    }

    private long grams(BigDecimal kilograms) {
        return kilograms.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private long secondsBetween(OffsetDateTime reference, OffsetDateTime value) {
        return Duration.between(reference.toInstant(), value.toInstant()).toSeconds();
    }

    private long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private long saturatedMultiply(long left, long right) {
        if (left == 0 || right == 0) return 0;
        if (left > 0 && right > 0 && left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private com.google.protobuf.Duration protobufDuration(java.time.Duration duration) {
        return com.google.protobuf.Duration.newBuilder()
                .setSeconds(duration.toSeconds())
                .setNanos(duration.toNanosPart())
                .build();
    }
}
