package com.azylivraison.planning.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Courier(
        String id,
        String name,
        VehicleType vehicleType,
        BigDecimal maxWeightKg,
        BigDecimal averageSpeedKmh,
        OffsetDateTime departureAt,
        Location departureLocation) {
}
