package com.azylivraison.delivery.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExpressDeliveryResponse(
        DeliveryRequestResponse request,
        List<Courier> couriers,
        BigDecimal totalWeightKg,
        BigDecimal selectedCapacityKg,
        boolean capacityWarning,
        Instant confirmedAt,
        boolean returnToPickup,
        boolean returnToPickupOverridden,
        ExpressFuelResponse fuelCalculation) {

    public ExpressDeliveryResponse {
        couriers = List.copyOf(couriers);
    }

    public record Courier(UUID id, String name) {}
}
