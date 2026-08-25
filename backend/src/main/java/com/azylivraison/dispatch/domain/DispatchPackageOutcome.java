package com.azylivraison.dispatch.domain;

import java.math.BigDecimal;
import java.time.Instant;
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
        DeliveryPricingSnapshot pricingSnapshot,
        BigDecimal effectiveWeightKg,
        Location destination) {

    public long cashToCollectAriary() {
        return mobileMoneyPaid ? 0 : amountToCollectAriary;
    }
}
