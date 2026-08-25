package com.azylivraison.dispatch.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.azylivraison.dispatch.domain.DispatchPackageOutcome;
import com.azylivraison.planning.domain.Location;

public record DispatchPackageResponse(
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
        long cashToCollectAriary,
        boolean mobileMoneyPaid,
        BigDecimal effectiveWeightKg,
        Location destination) {

    static DispatchPackageResponse from(DispatchPackageOutcome outcome) {
        return new DispatchPackageResponse(
                outcome.packageId(),
                outcome.deliveryRequestId(),
                outcome.assigned(),
                outcome.collectorCourierId(),
                outcome.distributorCourierId(),
                outcome.transferred(),
                outcome.estimatedDeliveryAt(),
                outcome.deliveryOrder(),
                outcome.unassignedReason(),
                outcome.unassignedMessage(),
                outcome.recipientName(),
                outcome.faritra(),
                outcome.contact(),
                outcome.amountToCollectAriary(),
                outcome.cashToCollectAriary(),
                outcome.mobileMoneyPaid(),
                outcome.effectiveWeightKg(),
                outcome.destination());
    }
}
