package com.azylivraison.optimization.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

record HubCollectionSolvedOptimization(
        String solverStatus,
        long computationTimeMillis,
        OffsetDateTime timeReference,
        long latestHubArrivalOffsetSeconds,
        List<SolvedRoute> routes,
        Set<Integer> droppedTaskIndexes) {

    HubCollectionSolvedOptimization {
        routes = List.copyOf(routes);
        droppedTaskIndexes = Set.copyOf(droppedTaskIndexes);
    }

    record SolvedRoute(
            int courierIndex,
            long departureOffsetSeconds,
            long hubArrivalOffsetSeconds,
            double distanceMeters,
            double hubLegDistanceMeters,
            List<SolvedVisit> visits) {

        SolvedRoute {
            visits = List.copyOf(visits);
        }
    }

    record SolvedVisit(
            int taskIndex,
            long arrivalOffsetSeconds,
            double distanceFromPreviousMeters,
            double travelDurationFromPreviousSeconds,
            long serviceDurationSeconds,
            double cumulativeDistanceMeters) {
    }
}
