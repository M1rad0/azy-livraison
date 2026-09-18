package com.azylivraison.administration.service;

import java.math.BigDecimal;

import com.azylivraison.administration.domain.CourierProfile;

public record EffectiveCourierProfile(
        CourierProfile courier,
        BigDecimal maxWeightKg,
        BigDecimal averageSpeedKmh,
        int commissionNumerator,
        int commissionDenominator,
        boolean fuelReimbursementEnabled,
        BigDecimal fuelEfficiencyKmPerLiter,
        long fuelPricePerLiterAriary) {
}
