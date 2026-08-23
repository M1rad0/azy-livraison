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
        LocalTime defaultDepartureTime,
        ConfirmedLocation departureLocation,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public CourierProfile withActive(boolean newActive) {
        return new CourierProfile(
                id, accountId, name, contact, phoneNumber, payoutDestination, newActive, vehicleType,
                maxWeightKg, averageSpeedKmh, defaultDepartureTime, departureLocation,
                version, createdAt, updatedAt);
    }
}
