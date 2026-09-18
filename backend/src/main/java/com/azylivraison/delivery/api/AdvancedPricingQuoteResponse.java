package com.azylivraison.delivery.api;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.pricing.domain.AdvancedPricingQuote;
import com.azylivraison.pricing.domain.PricingDistanceMode;

public record AdvancedPricingQuoteResponse(
        short pricingPolicyId,
        long pricingPolicyVersion,
        DeliveryRequestKind requestKind,
        PricingDistanceMode distanceMode,
        UUID pricingZoneId,
        String pricingZoneName,
        long distanceFromCenterMeters,
        long pricingZoneMaxDistanceMeters,
        long baseDeliveryFeeAriary,
        BigDecimal effectiveWeightKg,
        long weightSurchargeAriary,
        LocalTime desiredDeliveryTime,
        LocalTime desiredDeliveryEndTime,
        boolean desiredDeliveryEndsNextDay,
        String timeSlotLabel,
        long timeSurchargeAriary,
        long deliveryFeeAriary) {

    public static AdvancedPricingQuoteResponse from(AdvancedPricingQuote quote) {
        return new AdvancedPricingQuoteResponse(quote.pricingPolicyId(), quote.pricingPolicyVersion(),
                quote.requestKind(), quote.distanceMode(), quote.pricingZoneId(), quote.pricingZoneName(),
                quote.distanceFromCenterMeters(), quote.pricingZoneMaxDistanceMeters(),
                quote.baseDeliveryFeeAriary(), quote.effectiveWeightKg(), quote.weightSurchargeAriary(),
                quote.desiredDeliveryTime(),
                quote.desiredDeliveryTime() == null ? null : quote.desiredDeliveryTime().plusHours(1),
                quote.desiredDeliveryTime() != null
                        && quote.desiredDeliveryTime().plusHours(1).isBefore(quote.desiredDeliveryTime()),
                quote.timeSlotLabel(), quote.timeSurchargeAriary(),
                quote.deliveryFeeAriary());
    }
}
