package com.azylivraison.dispatch.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.VehicleType;

public record DispatchPlanCourierSnapshot(
        UUID courierId,
        String name,
        String contact,
        VehicleType vehicleType,
        BigDecimal maxWeightKg,
        BigDecimal averageSpeedKmh,
        Instant departureAt,
        Location departure,
        boolean used,
        boolean collectionRole,
        boolean distributionRole,
        Instant hubArrivalAt,
        Instant hubDepartureAt,
        Instant completedAt,
        BigDecimal loadAtHubArrivalKg,
        BigDecimal loadAtHubDepartureKg,
        Long routeDistanceMeters,
        Long routeDurationSeconds) {
}
