package com.azylivraison.routing.api;

import java.util.List;

import com.azylivraison.planning.domain.RoutingPreset;
import com.azylivraison.routing.domain.GeoJsonLineString;
import com.azylivraison.routing.domain.RoutingLeg;
import com.azylivraison.routing.domain.RoutingWaypoint;
import com.azylivraison.routing.service.RoutingRouteResult;

public record RoutingRouteResponse(
        String provider,
        RoutingPreset preset,
        int pointCount,
        double distanceMeters,
        double durationSeconds,
        GeoJsonLineString geometry,
        List<RoutingWaypoint> waypoints,
        List<RoutingLeg> legs) {

    static RoutingRouteResponse from(RoutingRouteResult result) {
        return new RoutingRouteResponse(
                result.provider(),
                result.preset(),
                result.waypoints().size(),
                result.distanceMeters(),
                result.durationSeconds(),
                result.geometry(),
                result.waypoints(),
                result.legs());
    }
}
