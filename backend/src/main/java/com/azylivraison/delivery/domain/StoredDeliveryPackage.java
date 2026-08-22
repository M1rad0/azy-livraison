package com.azylivraison.delivery.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
        BigDecimal effectiveWeightKg,
        PackageWeightSource weightSource,
        DeliveryPackageStatus status,
        String unassignedReason,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
