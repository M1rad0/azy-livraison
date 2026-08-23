package com.azylivraison.pricing.domain;

import java.util.UUID;

public record PricingZone(
        UUID id,
        String name,
        long maxDistanceMeters,
        long deliveryFeeAriary) {
}
