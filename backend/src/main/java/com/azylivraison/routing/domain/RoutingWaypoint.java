package com.azylivraison.routing.domain;

public record RoutingWaypoint(
        String id,
        double inputLatitude,
        double inputLongitude,
        double snappedLatitude,
        double snappedLongitude,
        double distanceToRoadMeters,
        String roadName) {
}
