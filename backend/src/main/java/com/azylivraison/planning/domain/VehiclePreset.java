package com.azylivraison.planning.domain;

import java.math.BigDecimal;

public record VehiclePreset(
        VehicleType type,
        BigDecimal maxWeightKg,
        BigDecimal averageSpeedKmh) {
}

