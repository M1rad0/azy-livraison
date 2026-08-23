package com.azylivraison.delivery.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestStatus;
import com.azylivraison.delivery.domain.DeliveryPackageStatus;
import com.azylivraison.delivery.domain.StoredDeliveryRequest;

public record DeliveryRequestResponse(
        UUID id,
        UUID ownerAccountId,
        String ownerDisplayName,
        String ownerPhone,
        LocalDate requestedDate,
        LocationResponse pickup,
        CapacitySnapshotResponse capacity,
        DeliveryRequestStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<DeliveryPackageResponse> packages) {

    public static DeliveryRequestResponse from(StoredDeliveryRequest request) {
        return from(request, false);
    }

    public static DeliveryRequestResponse fromPendingPackages(StoredDeliveryRequest request) {
        return from(request, true);
    }

    private static DeliveryRequestResponse from(StoredDeliveryRequest request, boolean pendingOnly) {
        return new DeliveryRequestResponse(
                request.id(),
                request.ownerAccountId(),
                request.ownerDisplayName(),
                request.ownerPhone(),
                request.requestedDate(),
                LocationResponse.from(request.pickup()),
                new CapacitySnapshotResponse(
                        request.capacityLimitSnapshot(),
                        request.capacityCurrentSnapshot(),
                        request.capacityRequestedSnapshot(),
                        request.capacityProjectedSnapshot(),
                        request.capacityOverflowWarning(),
                        request.capacityOverflowAccepted()),
                request.status(),
                request.version(),
                request.createdAt(),
                request.updatedAt(),
                request.packages().stream()
                        .filter(deliveryPackage -> !pendingOnly
                                || (deliveryPackage.status() == DeliveryPackageStatus.PENDING
                                    && deliveryPackage.finalOutcome() == null))
                        .map(DeliveryPackageResponse::from)
                        .toList());
    }
}
