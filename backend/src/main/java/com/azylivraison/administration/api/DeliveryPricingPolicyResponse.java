package com.azylivraison.administration.api;

import java.time.Instant;
import java.util.List;

import com.azylivraison.pricing.domain.PricingPolicy;

public record DeliveryPricingPolicyResponse(
        long version,
        PricingCenterResponse center,
        List<DeliveryPricingZoneResponse> zones,
        Instant createdAt,
        Instant updatedAt) {

    static DeliveryPricingPolicyResponse from(PricingPolicy policy) {
        return new DeliveryPricingPolicyResponse(
                policy.version(),
                PricingCenterResponse.from(policy.center()),
                policy.zones().stream().map(DeliveryPricingZoneResponse::from).toList(),
                policy.createdAt(),
                policy.updatedAt());
    }
}
