package com.azylivraison.delivery.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StoredDeliveryRequest(
        UUID id,
        UUID ownerAccountId,
        String ownerDisplayName,
        String ownerPhone,
        DeliveryRequestKind requestKind,
        ExpressDeliveryStatus expressStatus,
        UUID createdByAccountId,
        LocalDate requestedDate,
        ConfirmedLocation pickup,
        int capacityLimitSnapshot,
        int capacityCurrentSnapshot,
        int capacityRequestedSnapshot,
        int capacityProjectedSnapshot,
        boolean capacityOverflowWarning,
        boolean capacityOverflowAccepted,
        DeliveryRequestStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<StoredDeliveryPackage> packages) {

    public StoredDeliveryRequest {
        packages = List.copyOf(packages);
    }
}
