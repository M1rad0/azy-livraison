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
}
