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
        PricingCenter center,
        BigDecimal defaultWeightKg,
        List<PricingZone> zones,
        List<PricingWeightTier> weightTiers,
        List<PricingTimeSlot> timeSlots,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public AdvancedPricingPolicy {
        zones = List.copyOf(zones);
        weightTiers = List.copyOf(weightTiers);
        timeSlots = List.copyOf(timeSlots);
    }
}
