package com.azylivraison.administration.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.azylivraison.planning.domain.VehicleType;

public record VehiclePresetConfiguration(
        VehicleType vehicleType,
        BigDecimal maxWeightKg,
        BigDecimal averageSpeedKmh,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
