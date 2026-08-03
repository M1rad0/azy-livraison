package com.azylivraison.routing.provider;

import java.util.List;

import com.azylivraison.routing.domain.RoutingWaypoint;

public record RoutingMatrixData(
        List<RoutingWaypoint> waypoints,
        List<List<Double>> durationsSeconds,
        List<List<Double>> distancesMeters) {
}
