package com.azylivraison.delivery.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryPackageStatus;
import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.delivery.domain.PackageWeightSource;
import com.azylivraison.delivery.domain.StoredDeliveryPackage;
import com.azylivraison.pricing.domain.PricingDistanceMode;

public record DeliveryPackageResponse(
        UUID id,
        int sequenceNumber,
        String recipientName,
        boolean faritra,
        LocationResponse destination,
        String contact,
        long amountToCollectAriary,
        boolean mobileMoneyPaid,
        LocalTime desiredDeliveryTime,
        LocalTime desiredDeliveryEndTime,
        boolean desiredDeliveryEndsNextDay,
        UUID pricingZoneId,
        String pricingZoneName,
        Long deliveryFeeAriary,
        Long calculatedDeliveryFeeAriary,
        Long finalDeliveryFeeAriary,
        String deliveryFeeOverrideReason,
        Instant deliveryFeeOverriddenAt,
        long deliveryFeeVersion,
        Long distanceFromCenterMeters,
        PricingDistanceMode pricingDistanceMode,
        Long pricingZoneMaxDistanceMeters,
        Long pricingPolicyVersion,
        Short pricingPolicyId,
        Long baseDeliveryFeeAriary,
        Long weightSurchargeAriary,
        Long timeSurchargeAriary,
        BigDecimal effectiveWeightKg,
        PackageWeightSource weightSource,
        DeliveryPackageStatus status,
        String unassignedReason,
        DeliveryPackageOutcome finalOutcome,
        Long actualCollectedAriary,
        String outcomeNote,
        Instant outcomeRecordedAt,
        Instant returnCompletedAt,
        UUID carriedForwardFromPackageId,
        UUID dispatchPlanId,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    static DeliveryPackageResponse from(StoredDeliveryPackage deliveryPackage) {
        return new DeliveryPackageResponse(
                deliveryPackage.id(),
                deliveryPackage.sequenceNumber(),
                deliveryPackage.recipientName(),
                deliveryPackage.faritra(),
                LocationResponse.from(deliveryPackage.destination()),
                deliveryPackage.contact(),
                deliveryPackage.amountToCollectAriary(),
                deliveryPackage.mobileMoneyPaid(),
                deliveryPackage.desiredDeliveryTime(),
                deliveryPackage.desiredDeliveryTime() == null
                        ? null : deliveryPackage.desiredDeliveryTime().plusHours(1),
                deliveryPackage.desiredDeliveryTime() != null
                        && deliveryPackage.desiredDeliveryTime().plusHours(1)
                                .isBefore(deliveryPackage.desiredDeliveryTime()),
                deliveryPackage.pricingSnapshot().pricingZoneId(),
                deliveryPackage.pricingSnapshot().pricingZoneName(),
                deliveryPackage.finalDeliveryFeeAriary(),
                deliveryPackage.pricingSnapshot().deliveryFeeAriary(),
                deliveryPackage.finalDeliveryFeeAriary(),
                deliveryPackage.deliveryFeeOverrideReason(),
                deliveryPackage.deliveryFeeOverriddenAt(),
                deliveryPackage.deliveryFeeVersion(),
                deliveryPackage.pricingSnapshot().distanceFromCenterMeters(),
                deliveryPackage.pricingSnapshot().distanceMode(),
                deliveryPackage.pricingSnapshot().pricingZoneMaxDistanceMeters(),
                deliveryPackage.pricingSnapshot().pricingPolicyVersion(),
                deliveryPackage.pricingSnapshot().pricingPolicyId(),
                deliveryPackage.pricingSnapshot().baseDeliveryFeeAriary(),
                deliveryPackage.pricingSnapshot().weightSurchargeAriary(),
                deliveryPackage.pricingSnapshot().timeSurchargeAriary(),
                deliveryPackage.effectiveWeightKg(),
                deliveryPackage.weightSource(),
                deliveryPackage.status(),
                deliveryPackage.unassignedReason(),
                deliveryPackage.finalOutcome(),
                deliveryPackage.actualCollectedAriary(),
                deliveryPackage.outcomeNote(),
                deliveryPackage.outcomeRecordedAt(),
                deliveryPackage.returnCompletedAt(),
                deliveryPackage.carriedForwardFromPackageId(),
                deliveryPackage.dispatchPlanId(),
                deliveryPackage.version(),
                deliveryPackage.createdAt(),
                deliveryPackage.updatedAt());
    }
}
