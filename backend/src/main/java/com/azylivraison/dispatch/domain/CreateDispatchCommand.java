package com.azylivraison.dispatch.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.planning.domain.Location;

public record CreateDispatchCommand(
        LocalDate serviceDate,
        List<UUID> courierIds,
        List<UUID> packageIds,
        Location hubOverride,
        String idempotencyKey,
        UUID actorAccountId) {

    public CreateDispatchCommand {
        courierIds = courierIds == null ? List.of() : List.copyOf(courierIds);
        packageIds = packageIds == null ? List.of() : List.copyOf(packageIds);
    }
}
