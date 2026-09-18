package com.azylivraison.dispatch.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import com.azylivraison.dispatch.domain.DispatchPackageOutcome;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.pricing.domain.PricingDistanceMode;

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
        LocalTime desiredDeliveryTime,
        LocalTime desiredDeliveryEndTime,
        boolean desiredDeliveryEndsNextDay,
        UUID pricingZoneId,
        String pricingZoneName,
        Long deliveryFeeAriary,
        Long distanceFromCenterMeters,
        PricingDistanceMode pricingDistanceMode,
        Long pricingZoneMaxDistanceMeters,
        Long pricingPolicyVersion,
        Short pricingPolicyId,
        Long baseDeliveryFeeAriary,
        Long weightSurchargeAriary,
        Long timeSurchargeAriary,
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
                outcome.desiredDeliveryTime(),
                outcome.desiredDeliveryTime() == null
                        ? null : outcome.desiredDeliveryTime().plusHours(1),
                outcome.desiredDeliveryTime() != null
                        && outcome.desiredDeliveryTime().plusHours(1)
                                .isBefore(outcome.desiredDeliveryTime()),
                outcome.pricingSnapshot().pricingZoneId(),
                outcome.pricingSnapshot().pricingZoneName(),
                outcome.pricingSnapshot().deliveryFeeAriary(),
                outcome.pricingSnapshot().distanceFromCenterMeters(),
                outcome.pricingSnapshot().distanceMode(),
                outcome.pricingSnapshot().pricingZoneMaxDistanceMeters(),
                outcome.pricingSnapshot().pricingPolicyVersion(),
                outcome.pricingSnapshot().pricingPolicyId(),
                outcome.pricingSnapshot().baseDeliveryFeeAriary(),
                outcome.pricingSnapshot().weightSurchargeAriary(),
                outcome.pricingSnapshot().timeSurchargeAriary(),
                outcome.effectiveWeightKg(),
                outcome.destination());
    }
}
