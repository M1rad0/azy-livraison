package com.azylivraison.administration.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.azylivraison.administration.domain.VehiclePresetConfiguration;
import com.azylivraison.planning.domain.VehicleType;

public record VehiclePresetResponse(
        VehicleType vehicleType,
        BigDecimal maxWeightKg,
        BigDecimal averageSpeedKmh,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    static VehiclePresetResponse from(VehiclePresetConfiguration preset) {
        return new VehiclePresetResponse(
                preset.vehicleType(),
                preset.maxWeightKg(),
                preset.averageSpeedKmh(),
                preset.version(),
                preset.createdAt(),
                preset.updatedAt());
    }
}
