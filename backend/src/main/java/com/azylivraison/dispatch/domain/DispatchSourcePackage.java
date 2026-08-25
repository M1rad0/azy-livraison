package com.azylivraison.dispatch.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.planning.domain.Location;

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
        BigDecimal effectiveWeightKg) {
}
