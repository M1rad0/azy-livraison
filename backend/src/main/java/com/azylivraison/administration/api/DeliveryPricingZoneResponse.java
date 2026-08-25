package com.azylivraison.administration.api;

import java.util.UUID;

import com.azylivraison.pricing.domain.PricingZone;

public record DeliveryPricingZoneResponse(
        UUID id,
        String name,
        long maxDistanceMeters,
        long deliveryFeeAriary) {

    static DeliveryPricingZoneResponse from(PricingZone zone) {
        return new DeliveryPricingZoneResponse(
                zone.id(), zone.name(), zone.maxDistanceMeters(), zone.deliveryFeeAriary());
    }
}
