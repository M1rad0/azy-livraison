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
        Long distanceFromPreviousMeters,
        Long durationFromPreviousSeconds,
        Long cumulativeDistanceMeters,
        Long cumulativeDurationSeconds) {

    /** Compatibility constructor for measured automatic routes. */
    public DispatchStop(UUID id, UUID courierId, int order, String type, UUID deliveryRequestId,
            UUID packageId, String label, Location location, Instant estimatedArrivalAt,
            long distanceFromPreviousMeters, long durationFromPreviousSeconds,
            long cumulativeDistanceMeters, long cumulativeDurationSeconds) {
        this(id, courierId, order, type, deliveryRequestId, packageId, label, location, estimatedArrivalAt,
                Long.valueOf(distanceFromPreviousMeters), Long.valueOf(durationFromPreviousSeconds),
                Long.valueOf(cumulativeDistanceMeters), Long.valueOf(cumulativeDurationSeconds));
    }
}
