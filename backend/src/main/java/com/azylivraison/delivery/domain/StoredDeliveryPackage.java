package com.azylivraison.delivery.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.azylivraison.pricing.domain.DeliveryPricingSnapshot;

public record StoredDeliveryPackage(
        UUID id,
        UUID deliveryRequestId,
        int sequenceNumber,
        String recipientName,
        boolean faritra,
        ConfirmedLocation destination,
        String contact,
        long amountToCollectAriary,
        boolean mobileMoneyPaid,
        DeliveryPricingSnapshot pricingSnapshot,
        Long finalDeliveryFeeAriary,
        String deliveryFeeOverrideReason,
        Instant deliveryFeeOverriddenAt,
        UUID deliveryFeeOverriddenByAccountId,
        long deliveryFeeVersion,
        BigDecimal effectiveWeightKg,
        PackageWeightSource weightSource,
        DeliveryPackageStatus status,
        String unassignedReason,
        DeliveryPackageOutcome finalOutcome,
        Long actualCollectedAriary,
        String outcomeNote,
        Instant outcomeRecordedAt,
        UUID outcomeRecordedByAccountId,
        Instant returnCompletedAt,
        UUID returnCompletedByAccountId,
        UUID carriedForwardFromPackageId,
        UUID dispatchPlanId,
        Instant priorityAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
