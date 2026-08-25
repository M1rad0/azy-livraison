package com.azylivraison.pricing.domain;

import java.util.UUID;

public record DeliveryPricingQuote(
        UUID pricingZoneId,
        String pricingZoneName,
        long deliveryFeeAriary,
        long distanceFromCenterMeters,
        long pricingZoneMaxDistanceMeters,
        long pricingPolicyVersion) {

    public DeliveryPricingSnapshot toSnapshot() {
        return new DeliveryPricingSnapshot(
                pricingZoneId,
                pricingZoneName,
                deliveryFeeAriary,
                distanceFromCenterMeters,
                pricingZoneMaxDistanceMeters,
                pricingPolicyVersion);
    }
}
