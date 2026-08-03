package com.azylivraison.routing.provider;

import java.util.List;

import com.azylivraison.routing.domain.GeoJsonLineString;
import com.azylivraison.routing.domain.RoutingLeg;
import com.azylivraison.routing.domain.RoutingWaypoint;

public record RoutingRouteData(
        double distanceMeters,
        double durationSeconds,
        GeoJsonLineString geometry,
        List<RoutingWaypoint> waypoints,
        List<RoutingLeg> legs) {
}
