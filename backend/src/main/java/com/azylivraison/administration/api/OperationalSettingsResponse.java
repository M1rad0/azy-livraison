package com.azylivraison.administration.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.azylivraison.administration.domain.ConfirmedLocation;
import com.azylivraison.administration.domain.OperationalSettings;
import com.azylivraison.planning.domain.RoutingPreset;

public record OperationalSettingsResponse(
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

    static OperationalSettingsResponse from(OperationalSettings settings) {
        return new OperationalSettingsResponse(
                settings.dailyPackageLimit(),
                settings.defaultHub(),
                settings.defaultPackageWeightKg(),
                settings.pickupServiceMinutes(),
                settings.hubServiceMinutes(),
                settings.deliveryServiceMinutes(),
                settings.earlyToleranceMinutes(),
                settings.lateToleranceMinutes(),
                settings.optimizationTimeLimitSeconds(),
                settings.routingPreset(),
                settings.version(),
                settings.createdAt(),
                settings.updatedAt());
    }
}
