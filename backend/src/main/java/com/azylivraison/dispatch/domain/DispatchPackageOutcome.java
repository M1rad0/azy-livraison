package com.azylivraison.dispatch.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import com.azylivraison.planning.domain.Location;
import com.azylivraison.pricing.domain.DeliveryPricingSnapshot;
import com.azylivraison.delivery.domain.DeliveryTimePreference;

public record DispatchPackageOutcome(
        UUID packageId,
        UUID deliveryRequestId,
        boolean assigned,
        UUID collectorCourierId,
        UUID distributorCourierId,
        boolean transferred,
        Instant estimatedDeliveryAt,
        Integer deliveryOrder,
        String unassignedReason,
        String unassignedMessage,
        String recipientName,
        boolean faritra,
        String contact,
        long amountToCollectAriary,
        boolean mobileMoneyPaid,
        LocalTime desiredDeliveryTime,
        DeliveryPricingSnapshot pricingSnapshot,
        BigDecimal effectiveWeightKg,
        Location destination,
        LocalTime desiredDeliveryEndTime,
        DeliveryTimePreference deliveryTimePreference) {

    public DispatchPackageOutcome {
        if (deliveryTimePreference == null) deliveryTimePreference = desiredDeliveryTime == null ? DeliveryTimePreference.NONE : DeliveryTimePreference.CUSTOM;
        if (desiredDeliveryTime != null && desiredDeliveryEndTime == null) desiredDeliveryEndTime = desiredDeliveryTime.plusHours(1);
    }

    public DispatchPackageOutcome(UUID packageId, UUID deliveryRequestId, boolean assigned, UUID collectorCourierId,
            UUID distributorCourierId, boolean transferred, Instant estimatedDeliveryAt, Integer deliveryOrder,
            String unassignedReason, String unassignedMessage, String recipientName, boolean faritra, String contact,
            long amountToCollectAriary, boolean mobileMoneyPaid, LocalTime desiredDeliveryTime,
            DeliveryPricingSnapshot pricingSnapshot, BigDecimal effectiveWeightKg, Location destination) {
        this(packageId, deliveryRequestId, assigned, collectorCourierId, distributorCourierId, transferred, estimatedDeliveryAt,
                deliveryOrder, unassignedReason, unassignedMessage, recipientName, faritra, contact, amountToCollectAriary,
                mobileMoneyPaid, desiredDeliveryTime, pricingSnapshot, effectiveWeightKg, destination, null, null);
    }

    public long cashToCollectAriary() {
        // Mobile Money is only an informational flag in the current business
        // policy. The amount declared by the client remains the amount AZY
        // tracks and reconciles, regardless of how the recipient paid.
        return amountToCollectAriary;
    }
}
