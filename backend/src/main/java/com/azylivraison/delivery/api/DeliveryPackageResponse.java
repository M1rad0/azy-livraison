package com.azylivraison.delivery.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryPackageStatus;
import com.azylivraison.delivery.domain.PackageWeightSource;
import com.azylivraison.delivery.domain.StoredDeliveryPackage;

public record DeliveryPackageResponse(
        UUID id,
        int sequenceNumber,
        String recipientName,
        boolean faritra,
        LocationResponse destination,
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

    static DeliveryPackageResponse from(StoredDeliveryPackage deliveryPackage) {
        return new DeliveryPackageResponse(
                deliveryPackage.id(),
                deliveryPackage.sequenceNumber(),
                deliveryPackage.recipientName(),
                deliveryPackage.faritra(),
                LocationResponse.from(deliveryPackage.destination()),
                deliveryPackage.contact(),
                deliveryPackage.amountToCollectAriary(),
                deliveryPackage.mobileMoneyPaid(),
                deliveryPackage.effectiveWeightKg(),
                deliveryPackage.weightSource(),
                deliveryPackage.status(),
                deliveryPackage.unassignedReason(),
                deliveryPackage.version(),
                deliveryPackage.createdAt(),
                deliveryPackage.updatedAt());
    }
}
