package com.azylivraison.optimization.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.azylivraison.optimization.domain.OptimizationResult.RouteStopType;

record MultiClientSolvedOptimization(
        String solverStatus,
        long computationTimeMillis,
        OffsetDateTime timeReference,
        List<SolvedRoute> routes,
        Set<Integer> droppedJobIndexes) {

    MultiClientSolvedOptimization {
        routes = List.copyOf(routes);
        droppedJobIndexes = Set.copyOf(droppedJobIndexes);
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
            int jobIndex,
            RouteStopType type,
            long arrivalOffsetSeconds,
            double distanceFromPreviousMeters,
            double travelDurationFromPreviousSeconds,
            double cumulativeDistanceMeters) {
    }
}
