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
import com.azylivraison.planning.domain.Courier;
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
class HubCollectionOrToolsRoutingOptimizer {

    private static final long PROHIBITIVE_ARC_COST = 1_000_000_000_000L;
    private static final long MAX_REGULAR_COST = PROHIBITIVE_ARC_COST / 4;

    private final OptimizationProperties properties;
    private final long pickupServiceSeconds;

    HubCollectionOrToolsRoutingOptimizer(OptimizationProperties properties) {
        Loader.loadNativeLibraries();
        this.properties = properties;
        this.pickupServiceSeconds = properties.operationalPolicy().pickupServiceDuration().toSeconds();
    }

    HubCollectionSolvedOptimization solve(HubCollectionProblem problem) {
        long startedAt = System.nanoTime();
        OffsetDateTime reference = timeReference(problem);
        int vehicleCount = problem.couriers().size();
        int nodeCount = problem.matrixPointByNode().length;
        int[] ends = new int[vehicleCount];
        Arrays.fill(ends, problem.hubNode());

        RoutingIndexManager manager = new RoutingIndexManager(
                nodeCount,
                vehicleCount,
                problem.courierStartNodes(),
                ends);
        RoutingModel routing = new RoutingModel(manager);
        Map<Integer, Integer> taskByNode = taskIndexesByNode(problem);
        long maxArcDistance = maximumFiniteDistance(problem.distancesMeters());
        long horizon = timeHorizon(problem, reference, maxArcDistance);
        CostHierarchy costs = costHierarchy(problem, maxArcDistance, horizon);

        int distanceCallback = routing.registerTransitCallback((fromIndex, toIndex) -> distanceCost(
                problem,
                manager,
                routing,
                fromIndex,
                toIndex));
        routing.setArcCostEvaluatorOfAllVehicles(distanceCallback);

        int demandCallback = routing.registerUnaryTransitCallback(fromIndex -> {
            Integer taskIndex = taskByNode.get(manager.indexToNode(fromIndex));
            return taskIndex == null ? 0 : grams(problem.tasks().get(taskIndex).weightKg());
        });
        long[] capacities = problem.couriers().stream()
                .mapToLong(courier -> grams(courier.maxWeightKg()))
                .toArray();
        routing.addDimensionWithVehicleCapacity(demandCallback, 0, capacities, true, "Capacity");

        int[] timeCallbacks = new int[vehicleCount];
        long[] timeCapacities = new long[vehicleCount];
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            int courierIndex = vehicle;
            timeCallbacks[vehicle] = routing.registerTransitCallback((fromIndex, toIndex) -> transitSeconds(
                    problem,
                    manager,
                    routing,
                    taskByNode,
                    courierIndex,
                    reference,
                    fromIndex,
                    toIndex));
            timeCapacities[vehicle] = horizon;
        }
        routing.addDimensionWithVehicleTransitAndCapacity(
                timeCallbacks,
                0,
                timeCapacities,
                true,
                "Time");
        RoutingDimension timeDimension = routing.getMutableDimension("Time");
        timeDimension.setGlobalSpanCostCoefficient(costs.latestArrivalCoefficient());
        if (problem.latestHubArrivalAt() != null) {
            long latestHubArrival = Math.max(0, secondsBetween(reference, problem.latestHubArrivalAt()));
            for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
                timeDimension.cumulVar(routing.end(vehicle)).setMax(latestHubArrival);
            }
        }
        for (int vehicle = 0; vehicle < vehicleCount; vehicle++) {
            routing.addVariableMinimizedByFinalizer(timeDimension.cumulVar(routing.end(vehicle)));
        }

        for (int taskIndex = 0; taskIndex < problem.tasks().size(); taskIndex++) {
            long index = manager.nodeToIndex(problem.taskNodes()[taskIndex]);
            long penalty = saturatingMultiply(costs.dropPenaltyPerPackage(),
                    problem.tasks().get(taskIndex).packageCount(), MAX_REGULAR_COST);
            routing.addDisjunction(new long[]{index}, penalty);
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
            for (int taskIndex = 0; taskIndex < problem.tasks().size(); taskIndex++) {
                allDropped.add(taskIndex);
            }
            return new HubCollectionSolvedOptimization(
                    routing.status().name(),
                    computationTimeMillis,
                    reference,
                    0,
                    List.of(),
                    allDropped);
        }

        List<HubCollectionSolvedOptimization.SolvedRoute> routes = extractRoutes(
                problem,
                manager,
                routing,
                timeDimension,
                solution,
                taskByNode,
                reference);
        long latestArrival = routes.stream()
                .mapToLong(HubCollectionSolvedOptimization.SolvedRoute::hubArrivalOffsetSeconds)
                .max()
                .orElse(0);
        return new HubCollectionSolvedOptimization(
                routing.status().name(),
                computationTimeMillis,
                reference,
                latestArrival,
                routes,
                droppedTasks(problem, manager, routing, solution));
    }

    private List<HubCollectionSolvedOptimization.SolvedRoute> extractRoutes(
            HubCollectionProblem problem,
            RoutingIndexManager manager,
            RoutingModel routing,
            RoutingDimension timeDimension,
            Assignment solution,
            Map<Integer, Integer> taskByNode,
            OffsetDateTime reference) {
        List<HubCollectionSolvedOptimization.SolvedRoute> routes = new ArrayList<>();
        for (int vehicle = 0; vehicle < problem.couriers().size(); vehicle++) {
            if (!routing.isVehicleUsed(solution, vehicle)) {
                continue;
            }
            long index = routing.start(vehicle);
            double totalDistance = 0;
            double hubLegDistance = 0;
            List<VisitDraft> drafts = new ArrayList<>();
            while (!routing.isEnd(index)) {
                long nextIndex = solution.value(routing.nextVar(index));
                double arcDistance = rawDistance(problem, manager, routing, index, nextIndex);
                totalDistance += arcDistance;
                if (routing.isEnd(nextIndex)) {
                    hubLegDistance = arcDistance;
                } else {
                    Integer taskIndex = taskByNode.get(manager.indexToNode(nextIndex));
                    if (taskIndex != null) {
                        drafts.add(new VisitDraft(
                                taskIndex,
                                solution.min(timeDimension.cumulVar(nextIndex)),
                                arcDistance,
                                travelSeconds(arcDistance, problem.couriers().get(vehicle)),
                                totalDistance));
                    }
                }
                index = nextIndex;
            }

            List<HubCollectionSolvedOptimization.SolvedVisit> visits = new ArrayList<>();
            for (int visitIndex = 0; visitIndex < drafts.size(); visitIndex++) {
                VisitDraft draft = drafts.get(visitIndex);
                boolean followedBySameWithdrawal = visitIndex + 1 < drafts.size()
                        && sameWithdrawal(
                                problem.tasks().get(draft.taskIndex()),
                                problem.tasks().get(drafts.get(visitIndex + 1).taskIndex()));
                visits.add(new HubCollectionSolvedOptimization.SolvedVisit(
                        draft.taskIndex(),
                        draft.arrivalOffsetSeconds(),
                        draft.distanceFromPreviousMeters(),
                        draft.travelDurationFromPreviousSeconds(),
                        followedBySameWithdrawal ? 0 : pickupServiceSeconds,
                        draft.cumulativeDistanceMeters()));
            }

            routes.add(new HubCollectionSolvedOptimization.SolvedRoute(
                    vehicle,
                    secondsBetween(reference, problem.couriers().get(vehicle).departureAt()),
                    solution.min(timeDimension.cumulVar(index)),
                    totalDistance,
                    hubLegDistance,
                    visits));
        }
        return List.copyOf(routes);
    }

    private Set<Integer> droppedTasks(
            HubCollectionProblem problem,
            RoutingIndexManager manager,
            RoutingModel routing,
            Assignment solution) {
        Set<Integer> dropped = new HashSet<>();
        for (int taskIndex = 0; taskIndex < problem.tasks().size(); taskIndex++) {
            long index = manager.nodeToIndex(problem.taskNodes()[taskIndex]);
            if (solution.value(routing.nextVar(index)) == index) {
                dropped.add(taskIndex);
            }
        }
        return Set.copyOf(dropped);
    }

    private long transitSeconds(
            HubCollectionProblem problem,
            RoutingIndexManager manager,
            RoutingModel routing,
            Map<Integer, Integer> taskByNode,
            int courierIndex,
            OffsetDateTime reference,
            long fromIndex,
            long toIndex) {
        if (routing.isStart(fromIndex) && routing.isEnd(toIndex)) {
            return 0;
        }
        long distance = distanceCost(problem, manager, routing, fromIndex, toIndex);
        if (distance >= PROHIBITIVE_ARC_COST) {
            return PROHIBITIVE_ARC_COST;
        }
        long travel = Math.round(travelSeconds(distance, problem.couriers().get(courierIndex)));
        if (routing.isStart(fromIndex)) {
            return saturatingAdd(
                    secondsBetween(reference, problem.couriers().get(courierIndex).departureAt()),
                    travel,
                    PROHIBITIVE_ARC_COST);
        }
        return saturatingAdd(
                travel,
                pickupServiceSeconds(problem, manager, taskByNode, fromIndex, toIndex),
                PROHIBITIVE_ARC_COST);
    }

    private long pickupServiceSeconds(
            HubCollectionProblem problem,
            RoutingIndexManager manager,
            Map<Integer, Integer> taskByNode,
            long fromIndex,
            long toIndex) {
        Integer fromTask = taskByNode.get(manager.indexToNode(fromIndex));
        if (fromTask == null) {
            return 0;
        }
        Integer toTask = taskByNode.get(manager.indexToNode(toIndex));
        if (toTask != null && sameWithdrawal(problem.tasks().get(fromTask), problem.tasks().get(toTask))) {
            return 0;
        }
        return pickupServiceSeconds;
    }

    private boolean sameWithdrawal(HubCollectionTask first, HubCollectionTask second) {
        return first.deliveryGroupId().equals(second.deliveryGroupId());
    }

    private long distanceCost(
            HubCollectionProblem problem,
            RoutingIndexManager manager,
            RoutingModel routing,
            long fromIndex,
            long toIndex) {
        if (routing.isStart(fromIndex) && routing.isEnd(toIndex)) {
            return 0;
        }
        int fromNode = manager.indexToNode(fromIndex);
        int toNode = manager.indexToNode(toIndex);
        Double distance = problem.distancesMeters()
                .get(problem.matrixPointByNode()[fromNode])
                .get(problem.matrixPointByNode()[toNode]);
        return distance == null ? PROHIBITIVE_ARC_COST : Math.max(0, Math.round(distance));
    }

    private double rawDistance(
            HubCollectionProblem problem,
            RoutingIndexManager manager,
            RoutingModel routing,
            long fromIndex,
            long toIndex) {
        long cost = distanceCost(problem, manager, routing, fromIndex, toIndex);
        return cost >= PROHIBITIVE_ARC_COST ? 0 : cost;
    }

    private Map<Integer, Integer> taskIndexesByNode(HubCollectionProblem problem) {
        Map<Integer, Integer> indexes = new HashMap<>();
        for (int taskIndex = 0; taskIndex < problem.tasks().size(); taskIndex++) {
            indexes.put(problem.taskNodes()[taskIndex], taskIndex);
        }
        return Map.copyOf(indexes);
    }

    private OffsetDateTime timeReference(HubCollectionProblem problem) {
        return problem.couriers().stream()
                .map(Courier::departureAt)
                .min(Comparator.comparing(OffsetDateTime::toInstant))
                .orElseThrow(() -> new IllegalArgumentException("Au moins un livreur est nécessaire."));
    }

    private long timeHorizon(
            HubCollectionProblem problem,
            OffsetDateTime reference,
            long maxArcDistance) {
        long latestDeparture = problem.couriers().stream()
                .map(Courier::departureAt)
                .mapToLong(value -> secondsBetween(reference, value))
                .max()
                .orElse(0);
        double slowestSpeed = problem.couriers().stream()
                .map(Courier::averageSpeedKmh)
                .mapToDouble(BigDecimal::doubleValue)
                .min()
                .orElse(1);
        long travelAllowance = (long) Math.ceil(
                maxArcDistance * Math.max(1, problem.tasks().size() + 1L) * 3.6 / slowestSpeed);
        long serviceAllowance = saturatingMultiply(
                pickupServiceSeconds,
                problem.tasks().size(),
                PROHIBITIVE_ARC_COST / 2);
        return Math.max(
                86_400,
                saturatingAdd(latestDeparture,
                        saturatingAdd(travelAllowance, serviceAllowance, PROHIBITIVE_ARC_COST / 2),
                        PROHIBITIVE_ARC_COST / 2));
    }

    private CostHierarchy costHierarchy(
            HubCollectionProblem problem,
            long maxArcDistance,
            long horizon) {
        long maximumArcCount = Math.max(1, problem.tasks().size() + problem.couriers().size());
        long distanceUpperBound = saturatingMultiply(maxArcDistance, maximumArcCount, MAX_REGULAR_COST);
        long latestCoefficient = Math.max(1, Math.min(distanceUpperBound + 1, 10_000_000L));
        long nonDropUpperBound = saturatingAdd(
                distanceUpperBound,
                saturatingMultiply(horizon, latestCoefficient, MAX_REGULAR_COST),
                MAX_REGULAR_COST);
        long dropPenalty = Math.max(
                1_000_000L,
                Math.min(MAX_REGULAR_COST, saturatingAdd(nonDropUpperBound, 1_000_000L, MAX_REGULAR_COST)));
        return new CostHierarchy(latestCoefficient, dropPenalty);
    }

    private long maximumFiniteDistance(List<List<Double>> distances) {
        return distances.stream()
                .flatMap(List::stream)
                .filter(java.util.Objects::nonNull)
                .mapToLong(value -> Math.max(0, Math.round(value)))
                .max()
                .orElse(1L);
    }

    private long grams(BigDecimal kilograms) {
        return kilograms.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private double travelSeconds(double distanceMeters, Courier courier) {
        return distanceMeters * 3.6 / courier.averageSpeedKmh().doubleValue();
    }

    private long secondsBetween(OffsetDateTime reference, OffsetDateTime value) {
        return Duration.between(reference.toInstant(), value.toInstant()).toSeconds();
    }

    private long saturatingAdd(long first, long second, long maximum) {
        if (first >= maximum || second >= maximum || first > maximum - second) {
            return maximum;
        }
        return first + second;
    }

    private long saturatingMultiply(long first, long second, long maximum) {
        if (first == 0 || second == 0) {
            return 0;
        }
        if (first >= maximum || second >= maximum || first > maximum / second) {
            return maximum;
        }
        return first * second;
    }

    private com.google.protobuf.Duration protobufDuration(java.time.Duration duration) {
        return com.google.protobuf.Duration.newBuilder()
                .setSeconds(duration.toSeconds())
                .setNanos(duration.toNanosPart())
                .build();
    }

    private record CostHierarchy(long latestArrivalCoefficient, long dropPenaltyPerPackage) {
    }

    private record VisitDraft(
            int taskIndex,
            long arrivalOffsetSeconds,
            double distanceFromPreviousMeters,
            double travelDurationFromPreviousSeconds,
            double cumulativeDistanceMeters) {
    }
}
