package com.azylivraison.administration.service;

import java.math.BigDecimal;

import com.azylivraison.planning.domain.VehicleType;

public record VehiclePresetUpdateCommand(
        VehicleType vehicleType,
        BigDecimal maxWeightKg,
        BigDecimal averageSpeedKmh,
        Long version) {
}
