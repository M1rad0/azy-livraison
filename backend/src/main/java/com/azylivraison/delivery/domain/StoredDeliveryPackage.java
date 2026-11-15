package com.azylivraison.delivery.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
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
        LocalTime desiredDeliveryTime,
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
        Instant updatedAt,
        LocalTime desiredDeliveryEndTime,
        DeliveryTimePreference deliveryTimePreference,
        boolean canEdit) {

    public StoredDeliveryPackage(UUID id, UUID deliveryRequestId, int sequenceNumber,
            String recipientName, boolean faritra, ConfirmedLocation destination, String contact,
            long amountToCollectAriary, boolean mobileMoneyPaid, LocalTime desiredDeliveryTime,
            DeliveryPricingSnapshot pricingSnapshot, Long finalDeliveryFeeAriary,
            String deliveryFeeOverrideReason, Instant deliveryFeeOverriddenAt,
            UUID deliveryFeeOverriddenByAccountId, long deliveryFeeVersion, BigDecimal effectiveWeightKg,
            PackageWeightSource weightSource, DeliveryPackageStatus status, String unassignedReason,
            DeliveryPackageOutcome finalOutcome, Long actualCollectedAriary, String outcomeNote,
            Instant outcomeRecordedAt, UUID outcomeRecordedByAccountId, Instant returnCompletedAt,
            UUID returnCompletedByAccountId, UUID carriedForwardFromPackageId, UUID dispatchPlanId,
            Instant priorityAt, long version, Instant createdAt, Instant updatedAt) {
        this(id, deliveryRequestId, sequenceNumber, recipientName, faritra, destination, contact,
                amountToCollectAriary, mobileMoneyPaid, desiredDeliveryTime, pricingSnapshot,
                finalDeliveryFeeAriary, deliveryFeeOverrideReason, deliveryFeeOverriddenAt,
                deliveryFeeOverriddenByAccountId, deliveryFeeVersion, effectiveWeightKg, weightSource,
                status, unassignedReason, finalOutcome, actualCollectedAriary, outcomeNote,
                outcomeRecordedAt, outcomeRecordedByAccountId, returnCompletedAt, returnCompletedByAccountId,
                carriedForwardFromPackageId, dispatchPlanId, priorityAt, version, createdAt, updatedAt,
                desiredDeliveryTime == null ? null : desiredDeliveryTime.plusHours(1),
                desiredDeliveryTime == null ? DeliveryTimePreference.NONE : DeliveryTimePreference.CUSTOM,
                status == DeliveryPackageStatus.PENDING && finalOutcome == null && dispatchPlanId == null);
    }
}
