package com.azylivraison.dispatch.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.planning.domain.Location;

public record CreateDispatchCommand(
        LocalDate serviceDate,
        List<UUID> courierIds,
        Location hubOverride,
        String idempotencyKey,
        UUID actorAccountId,
        List<UUID> packageIds) {

    public CreateDispatchCommand {
        courierIds = courierIds == null ? List.of() : List.copyOf(courierIds);
        packageIds = packageIds == null ? null : List.copyOf(packageIds);
    }

    public CreateDispatchCommand(
            LocalDate serviceDate,
            List<UUID> courierIds,
            Location hubOverride,
            String idempotencyKey,
            UUID actorAccountId) {
        this(serviceDate, courierIds, hubOverride, idempotencyKey, actorAccountId, null);
    }
}
