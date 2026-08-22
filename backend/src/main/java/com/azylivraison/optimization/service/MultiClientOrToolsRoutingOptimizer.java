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
import com.azylivraison.optimization.domain.OptimizationResult.RouteStopType;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;

import org.springframework.stereotype.Component;

@Component
class MultiClientOrToolsRoutingOptimizer {

    private static final long PROHIBITIVE_ARC_COST = 1_000_000_000_000L;
    private static final long LATENESS_COST_PER_SECOND = 10_000L;
    private final OptimizationProperties properties;

    MultiClientOrToolsRoutingOptimizer(OptimizationProperties properties) {
        Loader.loadNativeLibraries();
        this.properties = properties;
    }

    MultiClientSolvedOptimization solve(MultiClientOptimizationProblem problem) {
        long startedAt = System.nanoTime();
        int actualNodeCount = problem.matrixPointByNode().length;
        int vehicleCount = problem.couriers().size();
        int syntheticStartCount = (int) Arrays.stream(problem.courierStartNodes())
                .filter(node -> node < 0)
                .count();
        int syntheticStartBase = actualNodeCount;
        int syntheticEndBase = syntheticStartBase + syntheticStartCount;
        int[] starts = new int[vehicleCount];
        int[] ends = new int[vehicleCount];
        int nextSyntheticStart = syntheticStartBase;
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            starts[vehicle] = problem.courierStartNodes()[vehicle] >= 0
                    ? problem.courierStartNodes()[vehicle]
                    : nextSyntheticStart++;
            ends[vehicle] = syntheticEndBase + vehicle;
        }

        RoutingIndexManager manager = new RoutingIndexManager(
                actualNodeCount + syntheticStartCount + vehicleCount,
                vehicleCount,
                starts,
                ends);
        RoutingModel routing = new RoutingModel(manager);
        long maxArcDistance = maximumFiniteDistance(problem.distancesMeters());
        OperationalPolicy policy = properties.operationalPolicy();
        long dropPenalty = safeDropPenalty(maxArcDistance, problem.jobs().size(), vehicleCount, policy);

        int distanceCallback = routing.registerTransitCallback((fromIndex, toIndex) -> distanceCost(
                problem,
                actualNodeCount,
                syntheticStartBase,
                syntheticEndBase,
                manager.indexToNode(fromIndex),
                manager.indexToNode(toIndex)));
        routing.setArcCostEvaluatorOfAllVehicles(distanceCallback);
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            routing.setFixedCostOfVehicle(properties.vehicleActivationPenaltyMeters(), vehicle);
        }

        Map<Integer, JobNode> jobsByNode = jobsByNode(problem);
        int demandCallback = routing.registerUnaryTransitCallback(fromIndex -> {
            JobNode jobNode = jobsByNode.get(manager.indexToNode(fromIndex));
            if (jobNode == null) return 0;
            long weight = grams(problem.jobs().get(jobNode.jobIndex()).deliveryPackage().weightKg());
            return jobNode.type() == RouteStopType.PICKUP ? weight : -weight;
        });
        long[] capacities = problem.couriers().stream()
                .mapToLong(courier -> grams(courier.maxWeightKg()))
                .toArray();
        routing.addDimensionWithVehicleCapacity(demandCallback, 0, capacities, true, "Capacity");

        OffsetDateTime reference = timeReference(problem);
        long horizon = timeHorizon(problem, reference, maxArcDistance);
        int[] timeCallbacks = new int[vehicleCount];
        long[] timeCapacities = new long[vehicleCount];
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            Courier courier = problem.couriers().get(vehicle);
            timeCallbacks[vehicle] = routing.registerTransitCallback((fromIndex, toIndex) -> {
                int fromNode = manager.indexToNode(fromIndex);
                int toNode = manager.indexToNode(toIndex);
                long travel = travelSeconds(
                        distanceCost(
                                problem,
                                actualNodeCount,
                                syntheticStartBase,
                                syntheticEndBase,
                                fromNode,
                                toNode),
                        courier);
                return saturatedAdd(travel, serviceSeconds(problem, jobsByNode, fromNode, toNode, policy));
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

        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            OffsetDateTime departureAt = problem.couriers().get(vehicle).departureAt() == null
                    ? problem.planDepartureAt()
                    : problem.couriers().get(vehicle).departureAt();
            long departure = secondsBetween(reference, departureAt);
            timeDimension.cumulVar(routing.start(vehicle)).setRange(departure, departure);
            routing.addVariableMinimizedByFinalizer(timeDimension.cumulVar(routing.end(vehicle)));
        }

        for (int jobIndex = 0; jobIndex < problem.jobs().size(); jobIndex++) {
            long pickupIndex = manager.nodeToIndex(problem.pickupNodes()[jobIndex]);
            long deliveryIndex = manager.nodeToIndex(problem.deliveryNodes()[jobIndex]);
            routing.addPickupAndDelivery(pickupIndex, deliveryIndex);
            routing.solver().addConstraint(routing.solver().makeEquality(
                    routing.activeVar(pickupIndex), routing.activeVar(deliveryIndex)));
            routing.solver().addConstraint(routing.solver().makeEquality(
                    routing.vehicleVar(pickupIndex), routing.vehicleVar(deliveryIndex)));
            routing.solver().addConstraint(routing.solver().makeLessOrEqual(
                    timeDimension.cumulVar(pickupIndex), timeDimension.cumulVar(deliveryIndex)));
            routing.addDisjunction(new long[]{pickupIndex}, 0);
            routing.addDisjunction(new long[]{deliveryIndex}, dropPenalty);

            DeliveryPackage deliveryPackage = problem.jobs().get(jobIndex).deliveryPackage();
            if (deliveryPackage.deliveryWindow() != null) {
                long requestedStart = secondsBetween(reference, deliveryPackage.deliveryWindow().startAt());
                long requestedEnd = secondsBetween(reference, deliveryPackage.deliveryWindow().endAt());
                long hardStart = Math.max(0, requestedStart - policy.deliveryWindowEarlyTolerance().toSeconds());
                long hardEnd = saturatedAdd(requestedEnd, policy.deliveryWindowLateTolerance().toSeconds());
                timeDimension.cumulVar(deliveryIndex).setRange(hardStart, Math.min(horizon, hardEnd));
                timeDimension.setCumulVarSoftUpperBound(
                        deliveryIndex,
                        requestedEnd,
                        LATENESS_COST_PER_SECOND);
            }
        }

        RoutingSearchParameters searchParameters = main.defaultRoutingSearchParameters()
                .toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PARALLEL_CHEAPEST_INSERTION)
                .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(protobufDuration(properties.timeLimit()))
                .build();
        Assignment solution = routing.solveWithParameters(searchParameters);
        long computationTimeMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        if (solution == null) {
            Set<Integer> allDropped = new HashSet<>();
            for (int index = 0; index < problem.jobs().size(); index++) allDropped.add(index);
            return new MultiClientSolvedOptimization(
                    routing.status().name(), computationTimeMillis, reference, List.of(), allDropped);
        }

        return new MultiClientSolvedOptimization(
                routing.status().name(),
                computationTimeMillis,
                reference,
                extractRoutes(problem, actualNodeCount, syntheticStartBase, syntheticEndBase,
                        manager, routing, timeDimension, solution, jobsByNode),
                droppedJobs(problem, manager, routing, solution));
    }

    private List<MultiClientSolvedOptimization.SolvedRoute> extractRoutes(
            MultiClientOptimizationProblem problem,
            int actualNodeCount,
            int syntheticStartBase,
            int syntheticEndBase,
            RoutingIndexManager manager,
            RoutingModel routing,
            RoutingDimension timeDimension,
            Assignment solution,
            Map<Integer, JobNode> jobsByNode) {
        List<MultiClientSolvedOptimization.SolvedRoute> routes = new ArrayList<>();
        OffsetDateTime reference = timeReference(problem);
        for (int vehicle = 0; vehicle < problem.couriers().size(); vehicle++) {
            long index = routing.start(vehicle);
            OffsetDateTime departureAt = problem.couriers().get(vehicle).departureAt() == null
                    ? problem.planDepartureAt()
                    : problem.couriers().get(vehicle).departureAt();
            long departure = secondsBetween(reference, departureAt);
            double totalDistance = 0;
            List<MultiClientSolvedOptimization.SolvedVisit> visits = new ArrayList<>();
            while (!routing.isEnd(index)) {
                int fromNode = manager.indexToNode(index);
                long nextIndex = solution.value(routing.nextVar(index));
                int toNode = manager.indexToNode(nextIndex);
                double arcDistance = rawDistance(
                        problem, actualNodeCount, syntheticStartBase, syntheticEndBase, fromNode, toNode);
                totalDistance += arcDistance;
                JobNode jobNode = jobsByNode.get(toNode);
                if (jobNode != null) {
                    long arrival = solution.min(timeDimension.cumulVar(nextIndex));
                    visits.add(new MultiClientSolvedOptimization.SolvedVisit(
                            jobNode.jobIndex(),
                            jobNode.type(),
                            arrival,
                            arcDistance,
                            travelSeconds(arcDistance, problem.couriers().get(vehicle)),
                            totalDistance));
                }
                index = nextIndex;
            }
            if (!visits.isEmpty()) {
                routes.add(new MultiClientSolvedOptimization.SolvedRoute(
                        vehicle,
                        departure,
                        solution.min(timeDimension.cumulVar(index)),
                        totalDistance,
                        visits));
            }
        }
        return List.copyOf(routes);
    }

    private Set<Integer> droppedJobs(
            MultiClientOptimizationProblem problem,
            RoutingIndexManager manager,
            RoutingModel routing,
            Assignment solution) {
        Set<Integer> dropped = new HashSet<>();
        for (int jobIndex = 0; jobIndex < problem.jobs().size(); jobIndex++) {
            long deliveryIndex = manager.nodeToIndex(problem.deliveryNodes()[jobIndex]);
            if (solution.value(routing.nextVar(deliveryIndex)) == deliveryIndex) dropped.add(jobIndex);
        }
        return dropped;
    }

    private Map<Integer, JobNode> jobsByNode(MultiClientOptimizationProblem problem) {
        Map<Integer, JobNode> nodes = new HashMap<>();
        for (int index = 0; index < problem.jobs().size(); index++) {
            nodes.put(problem.pickupNodes()[index], new JobNode(index, RouteStopType.PICKUP));
            nodes.put(problem.deliveryNodes()[index], new JobNode(index, RouteStopType.DELIVERY));
        }
        return Map.copyOf(nodes);
    }

    private long distanceCost(
            MultiClientOptimizationProblem problem,
            int actualNodeCount,
            int syntheticStartBase,
            int syntheticEndBase,
            int fromNode,
            int toNode) {
        if (toNode >= syntheticEndBase) return 0;
        if (fromNode >= syntheticStartBase && fromNode < syntheticEndBase) {
            return isPickupNode(problem, toNode) ? 0 : PROHIBITIVE_ARC_COST;
        }
        if (fromNode >= actualNodeCount || toNode >= actualNodeCount) return PROHIBITIVE_ARC_COST;
        if (isCourierStartNode(problem, toNode)) return PROHIBITIVE_ARC_COST;
        if (isCourierStartNode(problem, fromNode) && !isPickupNode(problem, toNode)) {
            return PROHIBITIVE_ARC_COST;
        }
        Double distance = problem.distancesMeters()
                .get(problem.matrixPointByNode()[fromNode])
                .get(problem.matrixPointByNode()[toNode]);
        return distance == null ? PROHIBITIVE_ARC_COST : Math.max(0, Math.round(distance));
    }

    private double rawDistance(
            MultiClientOptimizationProblem problem,
            int actualNodeCount,
            int syntheticStartBase,
            int syntheticEndBase,
            int fromNode,
            int toNode) {
        long cost = distanceCost(problem, actualNodeCount, syntheticStartBase, syntheticEndBase, fromNode, toNode);
        return cost >= PROHIBITIVE_ARC_COST ? 0 : cost;
    }

    private boolean isPickupNode(MultiClientOptimizationProblem problem, int node) {
        return Arrays.stream(problem.pickupNodes()).anyMatch(value -> value == node);
    }

    private boolean isCourierStartNode(MultiClientOptimizationProblem problem, int node) {
        return Arrays.stream(problem.courierStartNodes()).anyMatch(value -> value >= 0 && value == node);
    }

    private OffsetDateTime timeReference(MultiClientOptimizationProblem problem) {
        List<OffsetDateTime> dates = new ArrayList<>();
        dates.add(problem.planDepartureAt());
        problem.couriers().stream().map(Courier::departureAt).filter(java.util.Objects::nonNull).forEach(dates::add);
        problem.jobs().stream()
                .map(MultiClientOptimizationJob::deliveryPackage)
                .filter(item -> item.deliveryWindow() != null)
                .forEach(item -> dates.add(item.deliveryWindow().startAt()));
        return dates.stream().min(Comparator.comparing(OffsetDateTime::toInstant)).orElse(problem.planDepartureAt());
    }

    private long timeHorizon(
            MultiClientOptimizationProblem problem,
            OffsetDateTime reference,
            long maxArcDistance) {
        long latestConstraint = secondsBetween(reference, problem.planDepartureAt());
        for (Courier courier : problem.couriers()) {
            if (courier.departureAt() != null) {
                latestConstraint = Math.max(latestConstraint, secondsBetween(reference, courier.departureAt()));
            }
        }
        for (MultiClientOptimizationJob job : problem.jobs()) {
            if (job.deliveryPackage().deliveryWindow() != null) {
                latestConstraint = Math.max(latestConstraint,
                        secondsBetween(reference, job.deliveryPackage().deliveryWindow().endAt()));
            }
        }
        double slowestSpeed = problem.couriers().stream()
                .map(Courier::averageSpeedKmh)
                .mapToDouble(BigDecimal::doubleValue)
                .min()
                .orElse(1);
        long travelAllowance = (long) Math.ceil(
                maxArcDistance * Math.max(1, problem.jobs().size() * 2L + 1) * 3.6 / slowestSpeed);
        long serviceAllowance = saturatedAdd(
                properties.operationalPolicy().pickupServiceDuration().toSeconds() * Math.max(1, problem.jobs().size()),
                properties.operationalPolicy().deliveryServiceDuration().toSeconds() * Math.max(1, problem.jobs().size()));
        return Math.max(86_400, saturatedAdd(latestConstraint, saturatedAdd(travelAllowance, serviceAllowance + 86_400)));
    }

    private long maximumFiniteDistance(List<List<Double>> distances) {
        return distances.stream()
                .flatMap(List::stream)
                .filter(java.util.Objects::nonNull)
                .mapToLong(value -> Math.max(0, Math.round(value)))
                .max()
                .orElse(1L);
    }

    private long safeDropPenalty(
            long maxArcDistance,
            int jobCount,
            int vehicleCount,
            OperationalPolicy policy) {
        long multiplier = Math.max(2, jobCount * 2L + vehicleCount + 1L);
        try {
            long routeUpperBound = Math.multiplyExact(
                    maxArcDistance + properties.vehicleActivationPenaltyMeters(), multiplier);
            long latenessUpperBound = Math.multiplyExact(
                    Math.multiplyExact(policy.deliveryWindowLateTolerance().toSeconds(), LATENESS_COST_PER_SECOND),
                    Math.max(1, jobCount));
            return Math.min(PROHIBITIVE_ARC_COST / 2,
                    Math.addExact(Math.addExact(routeUpperBound, latenessUpperBound), 1_000_000L));
        } catch (ArithmeticException exception) {
            return PROHIBITIVE_ARC_COST / 2;
        }
    }

    private long serviceSeconds(
            MultiClientOptimizationProblem problem,
            Map<Integer, JobNode> jobsByNode,
            int fromNode,
            int toNode,
            OperationalPolicy policy) {
        JobNode from = jobsByNode.get(fromNode);
        if (from == null) return 0;
        if (from.type() == RouteStopType.DELIVERY) {
            return policy.deliveryServiceDuration().toSeconds();
        }

        JobNode to = jobsByNode.get(toNode);
        if (to != null && to.type() == RouteStopType.PICKUP) {
            String fromGroup = problem.jobs().get(from.jobIndex()).deliveryGroupId();
            String toGroup = problem.jobs().get(to.jobIndex()).deliveryGroupId();
            if (fromGroup.equals(toGroup)) return 0;
        }
        return policy.pickupServiceDuration().toSeconds();
    }

    private long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
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

    private com.google.protobuf.Duration protobufDuration(java.time.Duration duration) {
        return com.google.protobuf.Duration.newBuilder()
                .setSeconds(duration.toSeconds())
                .setNanos(duration.toNanosPart())
                .build();
    }

    private record JobNode(int jobIndex, RouteStopType type) {
    }
}
