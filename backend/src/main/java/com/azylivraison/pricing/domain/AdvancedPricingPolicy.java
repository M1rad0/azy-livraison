package com.azylivraison.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestKind;

public record AdvancedPricingPolicy(
        short id,
        UUID clientAccountId,
        DeliveryRequestKind requestKind,
        PricingDistanceMode distanceMode,
        PricingCenter center,
        BigDecimal defaultWeightKg,
        List<PricingZone> zones,
        List<PricingWeightTier> weightTiers,
        List<PricingTimeSlot> timeSlots,
        long morningSurchargeAriary,
        long afternoonSurchargeAriary,
        List<PricingKeywordRule> keywordRules,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public AdvancedPricingPolicy {
        if (distanceMode == null) {
            throw new IllegalArgumentException("Le mode de calcul de distance est obligatoire.");
        }
        zones = List.copyOf(zones);
        weightTiers = List.copyOf(weightTiers);
        timeSlots = List.copyOf(timeSlots);
        keywordRules = List.copyOf(keywordRules);
    }

    public AdvancedPricingPolicy(short id, UUID clientAccountId, DeliveryRequestKind requestKind,
            PricingDistanceMode distanceMode, PricingCenter center, BigDecimal defaultWeightKg,
            List<PricingZone> zones, List<PricingWeightTier> weightTiers, List<PricingTimeSlot> timeSlots,
            long version, Instant createdAt, Instant updatedAt) {
        this(id, clientAccountId, requestKind, distanceMode, center, defaultWeightKg, zones, weightTiers,
                timeSlots, 500, 500, List.of(), version, createdAt, updatedAt);
    }
}
