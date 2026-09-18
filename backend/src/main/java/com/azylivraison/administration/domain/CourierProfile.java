package com.azylivraison.administration.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import com.azylivraison.planning.domain.VehicleType;

public record CourierProfile(
        UUID id,
        UUID accountId,
        String name,
        String contact,
        String phoneNumber,
        CourierPayoutDestination payoutDestination,
        boolean active,
        VehicleType vehicleType,
        BigDecimal maxWeightKg,
        BigDecimal averageSpeedKmh,
        Integer commissionNumerator,
        Integer commissionDenominator,
        Boolean fuelReimbursementEnabled,
        BigDecimal fuelEfficiencyKmPerLiter,
        Long fuelPricePerLiterAriary,
        LocalTime defaultDepartureTime,
        ConfirmedLocation departureLocation,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public CourierProfile withActive(boolean newActive) {
        return new CourierProfile(
                id, accountId, name, contact, phoneNumber, payoutDestination, newActive, vehicleType,
                maxWeightKg, averageSpeedKmh, commissionNumerator, commissionDenominator,
                fuelReimbursementEnabled, fuelEfficiencyKmPerLiter, fuelPricePerLiterAriary,
                defaultDepartureTime, departureLocation,
                version, createdAt, updatedAt);
    }
}
