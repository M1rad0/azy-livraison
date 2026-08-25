package com.azylivraison.administration.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.azylivraison.planning.domain.RoutingPreset;

public record OperationalSettings(
        int dailyPackageLimit,
        ConfirmedLocation defaultHub,
        BigDecimal defaultPackageWeightKg,
        int pickupServiceMinutes,
        int hubServiceMinutes,
        int deliveryServiceMinutes,
        int earlyToleranceMinutes,
        int lateToleranceMinutes,
        int optimizationTimeLimitSeconds,
        RoutingPreset routingPreset,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
