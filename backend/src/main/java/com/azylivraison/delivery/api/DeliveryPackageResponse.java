package com.azylivraison.delivery.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryPackageStatus;
import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.delivery.domain.PackageWeightSource;
import com.azylivraison.delivery.domain.StoredDeliveryPackage;

public record DeliveryPackageResponse(
        UUID id,
        int sequenceNumber,
        String recipientName,
        boolean faritra,
        LocationResponse destination,
        String contact,
        long amountToCollectAriary,
        boolean mobileMoneyPaid,
        UUID pricingZoneId,
        String pricingZoneName,
        Long deliveryFeeAriary,
        Long distanceFromCenterMeters,
        Long pricingZoneMaxDistanceMeters,
        Long pricingPolicyVersion,
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
                deliveryPackage.pricingSnapshot().pricingZoneId(),
                deliveryPackage.pricingSnapshot().pricingZoneName(),
                deliveryPackage.pricingSnapshot().deliveryFeeAriary(),
                deliveryPackage.pricingSnapshot().distanceFromCenterMeters(),
                deliveryPackage.pricingSnapshot().pricingZoneMaxDistanceMeters(),
                deliveryPackage.pricingSnapshot().pricingPolicyVersion(),
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
