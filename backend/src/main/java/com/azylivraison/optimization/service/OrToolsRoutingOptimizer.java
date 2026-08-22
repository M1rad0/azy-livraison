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

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import com.azylivraison.optimization.config.OptimizationProperties;
import com.azylivraison.optimization.domain.OperationalPolicy;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;

import org.springframework.stereotype.Component;

@Component
class OrToolsRoutingOptimizer {

    private static final long PROHIBITIVE_ARC_COST = 1_000_000_000_000L;
    private static final long LATENESS_COST_PER_SECOND = 10_000L;
    private final OptimizationProperties properties;

    OrToolsRoutingOptimizer(OptimizationProperties properties) {
        Loader.loadNativeLibraries();
        this.properties = properties;
    }

    SolvedOptimization solve(OptimizationProblem problem) {
        long startedAt = System.nanoTime();
        int actualNodeCount = problem.distancesMeters().size();
        int vehicleCount = problem.couriers().size();
        int nodeCount = actualNodeCount + (problem.returnToDepot() ? 0 : vehicleCount);
        int[] ends = new int[vehicleCount];
        int[] starts = new int[vehicleCount];
        Arrays.fill(starts, problem.depotNode());
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            ends[vehicle] = problem.returnToDepot() ? problem.depotNode() : actualNodeCount + vehicle;
        }

        RoutingIndexManager manager = new RoutingIndexManager(
                nodeCount,
                vehicleCount,
                starts,
                ends);
        RoutingModel routing = new RoutingModel(manager);

        long maxArcDistance = maximumFiniteDistance(problem.distancesMeters());
        OperationalPolicy policy = properties.operationalPolicy();
        long dropPenalty = safeDropPenalty(maxArcDistance, problem.packages().size(), vehicleCount, policy);
        int distanceCallback = routing.registerTransitCallback((fromIndex, toIndex) -> distanceCost(
                problem,
                actualNodeCount,
                manager.indexToNode(fromIndex),
                manager.indexToNode(toIndex)));
        routing.setArcCostEvaluatorOfAllVehicles(distanceCallback);
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            long approachDistance = Math.max(0, Math.round(approachDistance(problem, vehicle)));
            routing.setFixedCostOfVehicle(
                    Math.min(PROHIBITIVE_ARC_COST, properties.vehicleActivationPenaltyMeters() + approachDistance),
                    vehicle);
        }

        Map<Integer, Integer> packageByNode = packageIndexByNode(problem);
        int demandCallback = routing.registerUnaryTransitCallback(fromIndex -> {
            Integer packageIndex = packageByNode.get(manager.indexToNode(fromIndex));
            return packageIndex == null ? 0L : grams(problem.packages().get(packageIndex).weightKg());
        });
        long[] capacities = problem.couriers().stream()
                .mapToLong(courier -> grams(courier.maxWeightKg()))
                .toArray();
        routing.addDimensionWithVehicleCapacity(demandCallback, 0, capacities, true, "Capacity");

        Arrays.stream(problem.courierStartNodes())
                .filter(node -> node != problem.depotNode())
                .distinct()
                .forEach(node -> routing.addDisjunction(new long[]{manager.nodeToIndex(node)}, 0));

        OffsetDateTime reference = timeReference(problem);
        long horizon = timeHorizon(problem, reference, maxArcDistance);
        int[] timeCallbacks = new int[vehicleCount];
        long[] timeCapacities = new long[vehicleCount];
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            Courier courier = problem.couriers().get(vehicle);
            timeCallbacks[vehicle] = routing.registerTransitCallback((fromIndex, toIndex) -> {
                int fromNode = manager.indexToNode(fromIndex);
                int toNode = manager.indexToNode(toIndex);
                long travel = travelSeconds(problem, actualNodeCount, fromNode, toNode, courier);
                long service = serviceSeconds(problem, fromNode, policy);
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

        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            OffsetDateTime departureAt = problem.couriers().get(vehicle).departureAt() == null
                    ? problem.planDepartureAt()
                    : problem.couriers().get(vehicle).departureAt();
            long departure = secondsBetween(reference, departureAt);
            long pickupArrival = departure + Math.round(travelSeconds(
                    approachDistance(problem, vehicle),
                    problem.couriers().get(vehicle)));
            timeDimension.cumulVar(routing.start(vehicle)).setRange(pickupArrival, pickupArrival);
            routing.addVariableMinimizedByFinalizer(timeDimension.cumulVar(routing.end(vehicle)));
        }

        for (int packageIndex = 0; packageIndex < problem.packages().size(); packageIndex++) {
            long routingIndex = manager.nodeToIndex(problem.packageNodes()[packageIndex]);
            DeliveryPackage deliveryPackage = problem.packages().get(packageIndex);
            if (deliveryPackage.deliveryWindow() != null) {
                long requestedStart = secondsBetween(reference, deliveryPackage.deliveryWindow().startAt());
                long requestedEnd = secondsBetween(reference, deliveryPackage.deliveryWindow().endAt());
                long hardStart = Math.max(0, requestedStart - policy.deliveryWindowEarlyTolerance().toSeconds());
                long hardEnd = saturatedAdd(requestedEnd, policy.deliveryWindowLateTolerance().toSeconds());
                timeDimension.cumulVar(routingIndex).setRange(hardStart, Math.min(horizon, hardEnd));
                timeDimension.setCumulVarSoftUpperBound(
                        routingIndex,
                        requestedEnd,
                        LATENESS_COST_PER_SECOND);
            }
            routing.addDisjunction(new long[]{routingIndex}, dropPenalty);
        }

        RoutingSearchParameters searchParameters = main.defaultRoutingSearchParameters()
                .toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(protobufDuration(properties.timeLimit()))
                .build();
        Assignment solution = routing.solveWithParameters(searchParameters);
        long computationTimeMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        if (solution == null) {
            Set<Integer> allDropped = new HashSet<>();
            for (int index = 0; index < problem.packages().size(); index++) allDropped.add(index);
            return new SolvedOptimization(
                    routing.status().name(),
                    computationTimeMillis,
                    reference,
                    List.of(),
                    allDropped);
        }

        Set<Integer> dropped = droppedPackages(problem, manager, routing, solution);
        List<SolvedOptimization.SolvedRoute> routes = extractRoutes(
                problem,
                actualNodeCount,
                manager,
                routing,
                timeDimension,
                solution,
                packageByNode);
        return new SolvedOptimization(
                routing.status().name(),
                computationTimeMillis,
                reference,
                routes,
                dropped);
    }

    private List<SolvedOptimization.SolvedRoute> extractRoutes(
            OptimizationProblem problem,
            int actualNodeCount,
            RoutingIndexManager manager,
            RoutingModel routing,
            RoutingDimension timeDimension,
            Assignment solution,
            Map<Integer, Integer> packageByNode) {
        List<SolvedOptimization.SolvedRoute> routes = new ArrayList<>();
        for (int vehicle = 0; vehicle < problem.couriers().size(); vehicle++) {
            long index = routing.start(vehicle);
            OffsetDateTime departureAt = problem.couriers().get(vehicle).departureAt() == null
                    ? problem.planDepartureAt()
                    : problem.couriers().get(vehicle).departureAt();
            long departure = secondsBetween(timeReference(problem), departureAt);
            double totalDistance = approachDistance(problem, vehicle);
            List<SolvedOptimization.SolvedVisit> visits = new ArrayList<>();
            while (!routing.isEnd(index)) {
                int fromNode = manager.indexToNode(index);
                long nextIndex = solution.value(routing.nextVar(index));
                int toNode = manager.indexToNode(nextIndex);
                double arcDistance = rawDistance(problem, actualNodeCount, fromNode, toNode);
                totalDistance += arcDistance;
                Integer packageIndex = packageByNode.get(toNode);
                if (packageIndex != null) {
                    long arrival = solution.min(timeDimension.cumulVar(nextIndex));
                    visits.add(new SolvedOptimization.SolvedVisit(
                            packageIndex,
                            arrival,
                            arcDistance,
                            travelSeconds(arcDistance, problem.couriers().get(vehicle)),
                            totalDistance));
                }
                index = nextIndex;
            }
            if (!visits.isEmpty()) {
                long completion = solution.min(timeDimension.cumulVar(index));
                routes.add(new SolvedOptimization.SolvedRoute(
                        vehicle,
                        departure,
                        completion,
                        totalDistance,
                        visits));
            }
        }
        return List.copyOf(routes);
    }

    private Set<Integer> droppedPackages(
            OptimizationProblem problem,
            RoutingIndexManager manager,
            RoutingModel routing,
            Assignment solution) {
        Set<Integer> dropped = new HashSet<>();
        for (int packageIndex = 0; packageIndex < problem.packages().size(); packageIndex++) {
            long index = manager.nodeToIndex(problem.packageNodes()[packageIndex]);
            if (solution.value(routing.nextVar(index)) == index) dropped.add(packageIndex);
        }
        return dropped;
    }

    private Map<Integer, Integer> packageIndexByNode(OptimizationProblem problem) {
        Map<Integer, Integer> indexes = new HashMap<>();
        for (int packageIndex = 0; packageIndex < problem.packageNodes().length; packageIndex++) {
            indexes.put(problem.packageNodes()[packageIndex], packageIndex);
        }
        return Map.copyOf(indexes);
    }

    private OffsetDateTime timeReference(OptimizationProblem problem) {
        List<OffsetDateTime> dates = new ArrayList<>();
        dates.add(problem.planDepartureAt());
        problem.couriers().stream().map(Courier::departureAt).filter(java.util.Objects::nonNull).forEach(dates::add);
        problem.packages().stream()
                .filter(item -> item.deliveryWindow() != null)
                .forEach(item -> dates.add(item.deliveryWindow().startAt()));
        return dates.stream().min(Comparator.comparing(OffsetDateTime::toInstant)).orElse(problem.planDepartureAt());
    }

    private long timeHorizon(OptimizationProblem problem, OffsetDateTime reference, long maxArcDistance) {
        long latestConstraint = secondsBetween(reference, problem.planDepartureAt());
        for (Courier courier : problem.couriers()) {
            if (courier.departureAt() != null) {
                latestConstraint = Math.max(latestConstraint, secondsBetween(reference, courier.departureAt()));
            }
        }
        for (DeliveryPackage deliveryPackage : problem.packages()) {
            if (deliveryPackage.deliveryWindow() != null) {
                latestConstraint = Math.max(
                        latestConstraint,
                        secondsBetween(reference, deliveryPackage.deliveryWindow().endAt()));
            }
        }
        double slowestSpeed = problem.couriers().stream()
                .map(Courier::averageSpeedKmh)
                .mapToDouble(BigDecimal::doubleValue)
                .min()
                .orElse(1);
        long travelAllowance = (long) Math.ceil(
                maxArcDistance * Math.max(1, problem.packages().size() + 1) * 3.6 / slowestSpeed);
        long serviceAllowance = saturatedAdd(
                properties.operationalPolicy().pickupServiceDuration().toSeconds(),
                properties.operationalPolicy().deliveryServiceDuration().toSeconds()
                        * Math.max(1, problem.packages().size()));
        return Math.max(86_400, saturatedAdd(latestConstraint, saturatedAdd(travelAllowance, serviceAllowance + 86_400)));
    }

    private long distanceCost(
            OptimizationProblem problem,
            int actualNodeCount,
            int fromNode,
            int toNode) {
        if (toNode >= actualNodeCount || fromNode >= actualNodeCount) return 0;
        if (isCourierOriginNode(problem, fromNode) || isCourierOriginNode(problem, toNode)) {
            return PROHIBITIVE_ARC_COST;
        }
        Double distance = problem.distancesMeters().get(fromNode).get(toNode);
        return distance == null ? PROHIBITIVE_ARC_COST : Math.max(0, Math.round(distance));
    }

    private double rawDistance(
            OptimizationProblem problem,
            int actualNodeCount,
            int fromNode,
            int toNode) {
        if (toNode >= actualNodeCount || fromNode >= actualNodeCount) return 0;
        if (isCourierOriginNode(problem, fromNode) || isCourierOriginNode(problem, toNode)) return 0;
        Double distance = problem.distancesMeters().get(fromNode).get(toNode);
        return distance == null ? 0 : distance;
    }

    private long travelSeconds(
            OptimizationProblem problem,
            int actualNodeCount,
            int fromNode,
            int toNode,
            Courier courier) {
        if (toNode >= actualNodeCount || fromNode >= actualNodeCount) return 0;
        if (isCourierOriginNode(problem, fromNode) || isCourierOriginNode(problem, toNode)) {
            return PROHIBITIVE_ARC_COST;
        }
        Double distance = problem.distancesMeters().get(fromNode).get(toNode);
        return distance == null ? PROHIBITIVE_ARC_COST : Math.round(travelSeconds(distance, courier));
    }

    private double travelSeconds(double distanceMeters, Courier courier) {
        return distanceMeters * 3.6 / courier.averageSpeedKmh().doubleValue();
    }

    private double approachDistance(OptimizationProblem problem, int vehicle) {
        return problem.courierApproachDistancesMeters()[vehicle];
    }

    private boolean isCourierOriginNode(OptimizationProblem problem, int node) {
        if (node == problem.depotNode()) return false;
        for (int startNode : problem.courierStartNodes()) {
            if (startNode == node) return true;
        }
        return false;
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
            int packageCount,
            int vehicleCount,
            OperationalPolicy policy) {
        long multiplier = Math.max(2, packageCount + vehicleCount + 1L);
        try {
            long routeUpperBound = Math.multiplyExact(
                    maxArcDistance + properties.vehicleActivationPenaltyMeters(), multiplier);
            long latenessUpperBound = Math.multiplyExact(
                    Math.multiplyExact(policy.deliveryWindowLateTolerance().toSeconds(), LATENESS_COST_PER_SECOND),
                    Math.max(1, packageCount));
            return Math.min(PROHIBITIVE_ARC_COST / 2,
                    Math.addExact(Math.addExact(routeUpperBound, latenessUpperBound), 1_000_000L));
        } catch (ArithmeticException exception) {
            return PROHIBITIVE_ARC_COST / 2;
        }
    }

    private long serviceSeconds(
            OptimizationProblem problem,
            int fromNode,
            OperationalPolicy policy) {
        if (fromNode == problem.depotNode()) {
            return policy.pickupServiceDuration().toSeconds();
        }
        for (int packageNode : problem.packageNodes()) {
            if (packageNode == fromNode) return policy.deliveryServiceDuration().toSeconds();
        }
        return 0;
    }

    private long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
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
}
