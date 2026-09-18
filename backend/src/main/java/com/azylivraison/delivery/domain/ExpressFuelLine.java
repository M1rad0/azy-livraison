package com.azylivraison.delivery.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ExpressFuelLine(
        UUID courierId,
        String courierName,
        String courierContact,
        List<UUID> packageIds,
        Long repositionDistanceMeters,
        Long automaticDistanceMeters,
        long finalDistanceMeters,
        boolean fuelReimbursementEnabled,
        BigDecimal fuelEfficiencyKmPerLiter,
        long fuelPricePerLiterAriary,
        long suggestedFuelAriary,
        long finalFuelAriary,
        String overrideReason,
        boolean manual) {

    public ExpressFuelLine {
        packageIds = List.copyOf(packageIds == null ? List.of() : packageIds);
    }
}
