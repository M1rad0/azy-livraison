package com.azylivraison.optimization.service;

import java.util.ArrayList;
import java.util.List;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import com.azylivraison.optimization.config.OptimizationProperties;

import org.springframework.stereotype.Component;

/** Optimizes one express courier's closed or open route over an OSRM distance matrix. */
@Component
public class ExpressRouteOptimizer {

    private static final long UNREACHABLE_COST = 1_000_000_000_000L;
    private final OptimizationProperties properties;

    public ExpressRouteOptimizer(OptimizationProperties properties) {
        Loader.loadNativeLibraries();
        this.properties = properties;
    }

    public Result optimize(List<List<Double>> distancesMeters, boolean returnToPickup) {
        int actualNodes = distancesMeters == null ? 0 : distancesMeters.size();
        validateSquareMatrix(distancesMeters, actualNodes);
        if (actualNodes < 2) return new Result(List.of(), 0);

        int endNode = returnToPickup ? 0 : actualNodes;
        int modelNodes = returnToPickup ? actualNodes : actualNodes + 1;
        RoutingIndexManager manager = new RoutingIndexManager(
                modelNodes, 1, new int[]{0}, new int[]{endNode});
        RoutingModel routing = new RoutingModel(manager);
        int distanceCallback = routing.registerTransitCallback((fromIndex, toIndex) -> {
            int from = manager.indexToNode(fromIndex);
            int to = manager.indexToNode(toIndex);
            if (from >= actualNodes || to >= actualNodes) return 0;
            Double distance = distancesMeters.get(from).get(to);
            return distance == null ? UNREACHABLE_COST : Math.max(0, Math.round(distance));
        });
        routing.setArcCostEvaluatorOfAllVehicles(distanceCallback);

        RoutingSearchParameters parameters = main.defaultRoutingSearchParameters().toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(com.google.protobuf.Duration.newBuilder()
                        .setSeconds(properties.timeLimit().toSeconds())
                        .setNanos(properties.timeLimit().toNanosPart())
                        .build())
                .build();
        Assignment solution = routing.solveWithParameters(parameters);
        if (solution == null) throw new IllegalStateException("Aucun trajet routier réalisable n'a été trouvé.");

        List<Integer> destinationIndexes = new ArrayList<>();
        long totalDistance = 0;
        long index = routing.start(0);
        while (!routing.isEnd(index)) {
            int from = manager.indexToNode(index);
            long nextIndex = solution.value(routing.nextVar(index));
            int to = manager.indexToNode(nextIndex);
            if (from < actualNodes && to < actualNodes) {
                Double distance = distancesMeters.get(from).get(to);
                if (distance == null) {
                    throw new IllegalStateException("Le trajet contient un segment routier inaccessible.");
                }
                totalDistance = Math.addExact(totalDistance, Math.max(0, Math.round(distance)));
            }
            if (to > 0 && to < actualNodes) destinationIndexes.add(to - 1);
            index = nextIndex;
        }
        return new Result(destinationIndexes, totalDistance);
    }

    private void validateSquareMatrix(List<List<Double>> matrix, int size) {
        if (matrix == null || size == 0 || matrix.stream().anyMatch(row -> row == null || row.size() != size)) {
            throw new IllegalArgumentException("La matrice routière est invalide.");
        }
    }

    public record Result(List<Integer> destinationIndexes, long distanceMeters) {
        public Result {
            destinationIndexes = List.copyOf(destinationIndexes);
        }
    }
}
