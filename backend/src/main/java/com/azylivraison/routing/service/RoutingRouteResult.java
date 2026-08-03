package com.azylivraison.routing.service;

import java.util.List;

import com.azylivraison.planning.domain.RoutingPreset;
import com.azylivraison.routing.domain.GeoJsonLineString;
import com.azylivraison.routing.domain.RoutingLeg;
import com.azylivraison.routing.domain.RoutingWaypoint;

public record RoutingRouteResult(
        String provider,
        RoutingPreset preset,
        double distanceMeters,
        double durationSeconds,
        GeoJsonLineString geometry,
        List<RoutingWaypoint> waypoints,
        List<RoutingLeg> legs) {
}
