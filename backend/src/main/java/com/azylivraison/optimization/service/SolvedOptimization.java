package com.azylivraison.optimization.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

record SolvedOptimization(
        String solverStatus,
        long computationTimeMillis,
        OffsetDateTime timeReference,
        List<SolvedRoute> routes,
        Set<Integer> droppedPackageIndexes) {

    SolvedOptimization {
        routes = List.copyOf(routes);
        droppedPackageIndexes = Set.copyOf(droppedPackageIndexes);
    }

    record SolvedRoute(
            int courierIndex,
            long departureOffsetSeconds,
            long completionOffsetSeconds,
            double distanceMeters,
            List<SolvedVisit> visits) {

        SolvedRoute {
            visits = List.copyOf(visits);
        }
    }

    record SolvedVisit(
            int packageIndex,
            long arrivalOffsetSeconds,
            double distanceFromPreviousMeters,
            double travelDurationFromPreviousSeconds,
            double cumulativeDistanceMeters) {
    }
}
