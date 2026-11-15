package com.azylivraison.dispatch.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.azylivraison.planning.domain.Location;
import com.azylivraison.pricing.domain.DeliveryPricingSnapshot;
import com.azylivraison.delivery.domain.DeliveryTimePreference;

/** Immutable database snapshot used to assemble one optimization input. */
public record DispatchSourcePackage(
        UUID packageId,
        UUID deliveryRequestId,
        UUID ownerAccountId,
        String serviceClientName,
        LocalDate requestedDate,
        Location pickup,
        String recipientName,
        boolean faritra,
        Location destination,
        String contact,
        long amountToCollectAriary,
        boolean mobileMoneyPaid,
        DeliveryPricingSnapshot pricingSnapshot,
        BigDecimal effectiveWeightKg,
        LocalTime desiredDeliveryTime,
        LocalTime desiredDeliveryEndTime,
        DeliveryTimePreference deliveryTimePreference) {

    public DispatchSourcePackage {
        if (deliveryTimePreference == null) deliveryTimePreference = desiredDeliveryTime == null ? DeliveryTimePreference.NONE : DeliveryTimePreference.CUSTOM;
        if (desiredDeliveryTime != null && desiredDeliveryEndTime == null) desiredDeliveryEndTime = desiredDeliveryTime.plusHours(1);
    }

    public DispatchSourcePackage(UUID packageId, UUID deliveryRequestId, UUID ownerAccountId, String serviceClientName,
            LocalDate requestedDate, Location pickup, String recipientName, boolean faritra, Location destination,
            String contact, long amountToCollectAriary, boolean mobileMoneyPaid, DeliveryPricingSnapshot pricingSnapshot,
            BigDecimal effectiveWeightKg, LocalTime desiredDeliveryTime) {
        this(packageId, deliveryRequestId, ownerAccountId, serviceClientName, requestedDate, pickup, recipientName, faritra,
                destination, contact, amountToCollectAriary, mobileMoneyPaid, pricingSnapshot, effectiveWeightKg, desiredDeliveryTime, null, null);
    }
}
