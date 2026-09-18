package com.azylivraison.pricing.domain;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestKind;

public record AdvancedPricingQuote(
        short pricingPolicyId,
        long pricingPolicyVersion,
        UUID clientAccountId,
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
        String timeSlotLabel,
        long timeSurchargeAriary,
        long deliveryFeeAriary) {

    public DeliveryPricingSnapshot toLegacySnapshot() {
        return new DeliveryPricingSnapshot(
                pricingZoneId, pricingZoneName, deliveryFeeAriary,
                distanceFromCenterMeters, distanceMode, pricingZoneMaxDistanceMeters, pricingPolicyVersion,
                pricingPolicyId, baseDeliveryFeeAriary, weightSurchargeAriary, timeSurchargeAriary);
    }
}
