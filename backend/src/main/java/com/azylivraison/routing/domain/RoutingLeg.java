package com.azylivraison.routing.domain;

public record RoutingLeg(
        int fromIndex,
        int toIndex,
        String fromId,
        String toId,
        double distanceMeters,
        double durationSeconds,
        String summary) {
}
