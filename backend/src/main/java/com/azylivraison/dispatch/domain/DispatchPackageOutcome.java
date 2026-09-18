package com.azylivraison.dispatch.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import com.azylivraison.planning.domain.Location;
import com.azylivraison.pricing.domain.DeliveryPricingSnapshot;

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
        Location destination) {

    public long cashToCollectAriary() {
        // Mobile Money is only an informational flag in the current business
        // policy. The amount declared by the client remains the amount AZY
        // tracks and reconciles, regardless of how the recipient paid.
        return amountToCollectAriary;
    }
}
