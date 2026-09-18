package com.azylivraison.pricing.domain;

import java.util.UUID;

/** Immutable tariff values captured when a package is registered. */
public record DeliveryPricingSnapshot(
        UUID pricingZoneId,
        String pricingZoneName,
        Long deliveryFeeAriary,
        Long distanceFromCenterMeters,
        PricingDistanceMode distanceMode,
        Long pricingZoneMaxDistanceMeters,
        Long pricingPolicyVersion,
        Short pricingPolicyId,
        Long baseDeliveryFeeAriary,
        Long weightSurchargeAriary,
        Long timeSurchargeAriary) {

    public DeliveryPricingSnapshot(UUID pricingZoneId, String pricingZoneName, Long deliveryFeeAriary,
            Long distanceFromCenterMeters, Long pricingZoneMaxDistanceMeters, Long pricingPolicyVersion) {
        this(pricingZoneId, pricingZoneName, deliveryFeeAriary, distanceFromCenterMeters,
                pricingZoneId == null ? null : PricingDistanceMode.AIR_DISTANCE,
                pricingZoneMaxDistanceMeters, pricingPolicyVersion, null, deliveryFeeAriary,
                deliveryFeeAriary == null ? null : 0L, deliveryFeeAriary == null ? null : 0L);
    }

    public DeliveryPricingSnapshot(UUID pricingZoneId, String pricingZoneName, Long deliveryFeeAriary,
            Long distanceFromCenterMeters, PricingDistanceMode distanceMode,
            Long pricingZoneMaxDistanceMeters, Long pricingPolicyVersion) {
        this(pricingZoneId, pricingZoneName, deliveryFeeAriary, distanceFromCenterMeters, distanceMode,
                pricingZoneMaxDistanceMeters, pricingPolicyVersion, null, deliveryFeeAriary,
                deliveryFeeAriary == null ? null : 0L, deliveryFeeAriary == null ? null : 0L);
    }

    public DeliveryPricingSnapshot {
        boolean allNull = pricingZoneId == null
                && pricingZoneName == null
                && deliveryFeeAriary == null
                && distanceFromCenterMeters == null
                && distanceMode == null
                && pricingZoneMaxDistanceMeters == null
                && pricingPolicyVersion == null;
        boolean allPresent = pricingZoneId != null
                && pricingZoneName != null
                && deliveryFeeAriary != null
                && distanceFromCenterMeters != null
                && distanceMode != null
                && pricingZoneMaxDistanceMeters != null
                && pricingPolicyVersion != null;
        if (!allNull && !allPresent) {
            throw new IllegalArgumentException("Un snapshot tarifaire doit etre complet ou absent.");
        }
    }

    public static DeliveryPricingSnapshot absent() {
        return new DeliveryPricingSnapshot(null, null, null, null, null, null, null,
                null, null, null, null);
    }

    public boolean present() {
        return pricingZoneId != null;
    }
}
