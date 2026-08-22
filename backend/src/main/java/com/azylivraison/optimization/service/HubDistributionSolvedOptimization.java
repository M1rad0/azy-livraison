package com.azylivraison.optimization.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

record HubDistributionSolvedOptimization(
        String solverStatus,
        long computationTimeMillis,
        OffsetDateTime timeReference,
        List<SolvedRoute> routes,
        Set<Integer> droppedJobIndexes) {

    HubDistributionSolvedOptimization {
        routes = List.copyOf(routes);
        droppedJobIndexes = Set.copyOf(droppedJobIndexes);
    }

    record SolvedRoute(
            int courierIndex,
            long departureOffsetSeconds,
            long completionOffsetSeconds,
            double distanceMeters,
            long drivingSeconds,
            long waitingSeconds,
            long serviceSeconds,
            List<SolvedVisit> visits) {

        SolvedRoute {
            visits = List.copyOf(visits);
        }
    }

    record SolvedVisit(
            int jobIndex,
            long arrivalOffsetSeconds,
            long serviceStartedOffsetSeconds,
            long serviceCompletedOffsetSeconds,
            long waitingBeforeServiceSeconds,
            long latenessSeconds,
            double distanceFromPreviousMeters,
            double travelDurationFromPreviousSeconds,
            double cumulativeDistanceMeters) {
    }
}
