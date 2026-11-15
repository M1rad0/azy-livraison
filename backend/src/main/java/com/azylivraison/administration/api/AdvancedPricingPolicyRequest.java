package com.azylivraison.administration.api;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.pricing.domain.PricingCenter;
import com.azylivraison.pricing.domain.PricingDistanceMode;
import com.azylivraison.pricing.domain.PricingKeywordRule;
import com.azylivraison.pricing.domain.PricingTimeSlot;
import com.azylivraison.pricing.domain.PricingWeightTier;
import com.azylivraison.pricing.domain.PricingZone;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AdvancedPricingPolicyRequest(
        UUID clientAccountId,
        @NotNull DeliveryRequestKind requestKind,
        PricingDistanceMode distanceMode,
        Long version,
        @NotNull @Valid PricingCenterRequest center,
        @NotNull @Positive BigDecimal defaultWeightKg,
        @NotEmpty @Size(max = 50) List<@Valid Zone> zones,
        @Size(max = 50) List<@Valid WeightTier> weightTiers,
        @Size(max = 50) List<@Valid TimeSlot> timeSlots,
        @PositiveOrZero Long morningSurchargeAriary,
        @PositiveOrZero Long afternoonSurchargeAriary,
        @Size(max = 100) List<@NotNull @Valid KeywordRule> keywordRules) {

    public AdvancedPricingPolicyRequest {
        if (distanceMode == null) distanceMode = PricingDistanceMode.AIR_DISTANCE;
        if (morningSurchargeAriary == null) morningSurchargeAriary = 500L;
        if (afternoonSurchargeAriary == null) afternoonSurchargeAriary = 500L;
    }

    public PricingCenter centerDomain() { return center.toDomain(); }
    public List<PricingZone> zoneDomains() {
        return zones.stream().map(item -> new PricingZone(item.id(), item.name(),
                item.maxDistanceMeters(), item.deliveryFeeAriary())).toList();
    }
    public List<PricingWeightTier> weightDomains() {
        return weightTiers == null ? List.of() : weightTiers.stream().map(item ->
                new PricingWeightTier(item.id(), item.maxWeightKg(), item.surchargeAriary())).toList();
    }
    public List<PricingTimeSlot> timeDomains() {
        return timeSlots == null ? List.of() : timeSlots.stream().map(item ->
                new PricingTimeSlot(item.id(), item.label(), item.startTime(), item.endTime(),
                        item.surchargeAriary())).toList();
    }

    public List<PricingKeywordRule> keywordDomains() {
        return keywordRules == null ? List.of() : keywordRules.stream().map(item ->
                new PricingKeywordRule(item.id(), item.keyword(), item.deliveryFeeAriary())).toList();
    }

    public AdvancedPricingPolicyRequest(UUID clientAccountId, DeliveryRequestKind requestKind,
            PricingDistanceMode distanceMode, Long version, PricingCenterRequest center, BigDecimal defaultWeightKg,
            List<Zone> zones, List<WeightTier> weightTiers, List<TimeSlot> timeSlots) {
        this(clientAccountId, requestKind, distanceMode, version, center, defaultWeightKg, zones, weightTiers,
                timeSlots, 500L, 500L, List.of());
    }

    public record Zone(UUID id, @NotNull @Size(min = 1, max = 100) String name,
            @Positive long maxDistanceMeters, @PositiveOrZero long deliveryFeeAriary) {}
    public record WeightTier(UUID id, @NotNull @Positive BigDecimal maxWeightKg,
            @PositiveOrZero long surchargeAriary) {}
    public record TimeSlot(UUID id, @NotNull @Size(min = 1, max = 100) String label,
            @NotNull LocalTime startTime, @NotNull LocalTime endTime,
            @PositiveOrZero long surchargeAriary) {}
    public record KeywordRule(UUID id, @NotNull @Size(min = 1, max = 200) String keyword,
            @PositiveOrZero long deliveryFeeAriary) {}
}
