package com.azylivraison.administration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import com.azylivraison.administration.domain.ConfirmedLocation;
import com.azylivraison.administration.domain.CourierProfile;
import com.azylivraison.administration.service.EffectiveCourierProfile;
import com.azylivraison.planning.domain.VehicleType;

public record CourierResponse(
        UUID id,
        UUID accountId,
        String name,
        String contact,
        String phoneNumber,
        PayoutDestinationResponse payoutDestination,
        boolean active,
        VehicleType vehicleType,
        BigDecimal maxWeightKg,
        BigDecimal averageSpeedKmh,
        BigDecimal effectiveMaxWeightKg,
        BigDecimal effectiveAverageSpeedKmh,
        LocalTime defaultDepartureTime,
        ConfirmedLocation departureLocation,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    static CourierResponse from(EffectiveCourierProfile effective) {
        CourierProfile courier = effective.courier();
        return new CourierResponse(
                courier.id(),
                courier.accountId(),
                courier.name(),
                courier.contact(),
                courier.phoneNumber(),
                PayoutDestinationResponse.from(courier.payoutDestination()),
                courier.active(),
                courier.vehicleType(),
                courier.maxWeightKg(),
                courier.averageSpeedKmh(),
                effective.maxWeightKg(),
                effective.averageSpeedKmh(),
                courier.defaultDepartureTime(),
                courier.departureLocation(),
                courier.version(),
                courier.createdAt(),
                courier.updatedAt());
    }

    public record PayoutDestinationResponse(String operator, String accountHolder, String number) {
        private static PayoutDestinationResponse from(
                com.azylivraison.administration.domain.CourierPayoutDestination payout) {
            return payout == null ? null : new PayoutDestinationResponse(
                    payout.operator(), payout.accountHolder(), payout.number());
        }
    }
}
