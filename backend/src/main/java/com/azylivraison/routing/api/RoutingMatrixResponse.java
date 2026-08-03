package com.azylivraison.routing.api;

import java.util.List;

import com.azylivraison.planning.domain.RoutingPreset;
import com.azylivraison.routing.domain.RoutingWaypoint;
import com.azylivraison.routing.domain.UnreachablePair;
import com.azylivraison.routing.service.RoutingMatrixResult;

public record RoutingMatrixResponse(
        String provider,
        RoutingPreset preset,
        int pointCount,
        List<RoutingWaypoint> waypoints,
        List<List<Double>> durationsSeconds,
        List<List<Double>> distancesMeters,
        List<UnreachablePair> unreachablePairs) {

    static RoutingMatrixResponse from(RoutingMatrixResult result) {
        return new RoutingMatrixResponse(
                result.provider(),
                result.preset(),
                result.waypoints().size(),
                result.waypoints(),
                result.durationsSeconds(),
                result.distancesMeters(),
                result.unreachablePairs());
    }
}
