package com.azylivraison.pricing.domain;

import java.time.Instant;
import java.util.List;

public record PricingPolicy(
        PricingCenter center,
        List<PricingZone> zones,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public PricingPolicy {
        zones = List.copyOf(zones);
    }
}
