package com.azylivraison.delivery.api;

import java.util.UUID;

import com.azylivraison.pricing.domain.DeliveryPricingQuote;

public record DeliveryPricingQuoteResponse(
        UUID pricingZoneId,
        String pricingZoneName,
        long deliveryFeeAriary,
        long distanceFromCenterMeters,
        long pricingZoneMaxDistanceMeters,
        long pricingPolicyVersion) {

    static DeliveryPricingQuoteResponse from(DeliveryPricingQuote quote) {
        return new DeliveryPricingQuoteResponse(
                quote.pricingZoneId(),
                quote.pricingZoneName(),
                quote.deliveryFeeAriary(),
                quote.distanceFromCenterMeters(),
                quote.pricingZoneMaxDistanceMeters(),
                quote.pricingPolicyVersion());
    }
}
