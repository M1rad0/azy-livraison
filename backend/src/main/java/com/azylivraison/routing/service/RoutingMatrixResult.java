package com.azylivraison.routing.service;

import java.util.List;

import com.azylivraison.planning.domain.RoutingPreset;
import com.azylivraison.routing.domain.RoutingWaypoint;
import com.azylivraison.routing.domain.UnreachablePair;

public record RoutingMatrixResult(
        String provider,
        RoutingPreset preset,
        List<RoutingWaypoint> waypoints,
        List<List<Double>> durationsSeconds,
        List<List<Double>> distancesMeters,
        List<UnreachablePair> unreachablePairs) {
}
