package com.azylivraison.administration.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

import com.azylivraison.administration.domain.ConfirmedLocation;
import com.azylivraison.administration.domain.CourierPayoutDestination;
import com.azylivraison.planning.domain.VehicleType;

public record CourierProfileCommand(
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
        LocalTime defaultDepartureTime,
        ConfirmedLocation departureLocation) {
}
