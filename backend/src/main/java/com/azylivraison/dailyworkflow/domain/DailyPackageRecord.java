package com.azylivraison.dailyworkflow.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.delivery.domain.ConfirmedLocation;
import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.delivery.domain.DeliveryPackageStatus;
import com.azylivraison.delivery.domain.DeliveryRequestKind;

public record DailyPackageRecord(
        UUID packageId,
        UUID deliveryRequestId,
        UUID ownerAccountId,
        String ownerDisplayName,
        String ownerPhone,
        DeliveryRequestKind requestKind,
        LocalDate requestedDate,
        ConfirmedLocation pickup,
        int sequenceNumber,
        String recipientName,
        boolean faritra,
        ConfirmedLocation destination,
        String contact,
        long amountToCollectAriary,
        boolean mobileMoneyPaid,
        UUID pricingZoneId,
        String pricingZoneName,
        Long deliveryFeeAriary,
        Long finalDeliveryFeeAriary,
        String deliveryFeeOverrideReason,
        Instant deliveryFeeOverriddenAt,
        long deliveryFeeVersion,
        DeliveryPackageStatus technicalStatus,
        String unassignedReason,
        DeliveryPackageOutcome finalOutcome,
        Long actualCollectedAriary,
        String outcomeNote,
        Instant outcomeRecordedAt,
        Instant returnCompletedAt,
        UUID carriedForwardFromPackageId,
        UUID carriedForwardToPackageId,
        UUID dispatchPlanId,
        UUID distributorCourierId,
        String distributorCourierName,
        UUID actualCourierId,
        String actualCourierName,
        String actualCourierContact,
        String actualCourierVehicleType,
        Instant estimatedDeliveryAt,
        Instant priorityAt,
        long version) {

    public boolean finalState() {
        return finalOutcome != null;
    }

    public boolean deliveryFeeCharged() {
        return finalOutcome == DeliveryPackageOutcome.DELIVERED
                || finalOutcome == DeliveryPackageOutcome.RETURN_TO_SENDER;
    }

    public long recoveredAriary() {
        return actualCollectedAriary == null ? 0 : actualCollectedAriary;
    }

    public long chargedDeliveryFeeAriary() {
        Long appliedFee = finalDeliveryFeeAriary == null ? deliveryFeeAriary : finalDeliveryFeeAriary;
        return deliveryFeeCharged() && appliedFee != null ? appliedFee : 0;
    }

    public long amountToRemitAriary() {
        return Math.subtractExact(recoveredAriary(), chargedDeliveryFeeAriary());
    }
}
