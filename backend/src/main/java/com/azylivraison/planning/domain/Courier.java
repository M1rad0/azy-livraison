package com.azylivraison.planning.domain;

import java.math.BigDecimal;

public record Courier(
        String id,
        String name,
        VehicleType vehicleType,
        BigDecimal maxWeightKg,
        BigDecimal averageSpeedKmh) {
}

