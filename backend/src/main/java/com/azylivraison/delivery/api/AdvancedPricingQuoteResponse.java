package com.azylivraison.delivery.api;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.pricing.domain.AdvancedPricingQuote;

public record AdvancedPricingQuoteResponse(
        short pricingPolicyId,
        long pricingPolicyVersion,
        DeliveryRequestKind requestKind,
        UUID pricingZoneId,
        String pricingZoneName,
        long distanceFromCenterMeters,
        long pricingZoneMaxDistanceMeters,
        long baseDeliveryFeeAriary,
        BigDecimal effectiveWeightKg,
        long weightSurchargeAriary,
        LocalTime desiredDeliveryTime,
        String timeSlotLabel,
        long timeSurchargeAriary,
        long deliveryFeeAriary) {

    public static AdvancedPricingQuoteResponse from(AdvancedPricingQuote quote) {
        return new AdvancedPricingQuoteResponse(quote.pricingPolicyId(), quote.pricingPolicyVersion(),
                quote.requestKind(), quote.pricingZoneId(), quote.pricingZoneName(),
                quote.distanceFromCenterMeters(), quote.pricingZoneMaxDistanceMeters(),
                quote.baseDeliveryFeeAriary(), quote.effectiveWeightKg(), quote.weightSurchargeAriary(),
                quote.desiredDeliveryTime(), quote.timeSlotLabel(), quote.timeSurchargeAriary(),
                quote.deliveryFeeAriary());
    }
}
