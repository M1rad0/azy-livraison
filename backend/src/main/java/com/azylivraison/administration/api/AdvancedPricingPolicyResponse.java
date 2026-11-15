package com.azylivraison.administration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.pricing.domain.AdvancedPricingPolicy;
import com.azylivraison.pricing.domain.PricingDistanceMode;

public record AdvancedPricingPolicyResponse(
        short id,
        UUID clientAccountId,
        DeliveryRequestKind requestKind,
        PricingDistanceMode distanceMode,
        boolean clientSpecific,
        long version,
        PricingCenterResponse center,
        BigDecimal defaultWeightKg,
        List<Zone> zones,
        List<WeightTier> weightTiers,
        List<TimeSlot> timeSlots,
        long morningSurchargeAriary,
        long afternoonSurchargeAriary,
        List<KeywordRule> keywordRules,
        Instant createdAt,
        Instant updatedAt) {

    public static AdvancedPricingPolicyResponse from(AdvancedPricingPolicy policy) {
        return new AdvancedPricingPolicyResponse(policy.id(), policy.clientAccountId(), policy.requestKind(),
                policy.distanceMode(), policy.clientAccountId() != null, policy.version(),
                PricingCenterResponse.from(policy.center()),
                policy.defaultWeightKg(),
                policy.zones().stream().map(item -> new Zone(item.id(), item.name(),
                        item.maxDistanceMeters(), item.deliveryFeeAriary())).toList(),
                policy.weightTiers().stream().map(item -> new WeightTier(item.id(), item.maxWeightKg(),
                        item.surchargeAriary())).toList(),
                policy.timeSlots().stream().map(item -> new TimeSlot(item.id(), item.label(), item.startTime(),
                        item.endTime(), item.surchargeAriary())).toList(),
                policy.morningSurchargeAriary(), policy.afternoonSurchargeAriary(),
                policy.keywordRules().stream().map(item -> new KeywordRule(item.id(), item.keyword(),
                        item.deliveryFeeAriary())).toList(),
                policy.createdAt(), policy.updatedAt());
    }

    public record Zone(UUID id, String name, long maxDistanceMeters, long deliveryFeeAriary) {}
    public record WeightTier(UUID id, BigDecimal maxWeightKg, long surchargeAriary) {}
    public record TimeSlot(UUID id, String label, LocalTime startTime, LocalTime endTime,
            long surchargeAriary) {}
    public record KeywordRule(UUID id, String keyword, long deliveryFeeAriary) {}
}
