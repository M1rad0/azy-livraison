package com.azylivraison.dispatch.domain;

import java.time.Instant;
import java.util.UUID;

import com.azylivraison.planning.domain.Location;

public record DispatchStop(
        UUID id,
        UUID courierId,
        int order,
        String type,
        UUID deliveryRequestId,
        UUID packageId,
        String label,
        Location location,
        Instant estimatedArrivalAt,
        long distanceFromPreviousMeters,
        long durationFromPreviousSeconds,
        long cumulativeDistanceMeters,
        long cumulativeDurationSeconds) {
}
