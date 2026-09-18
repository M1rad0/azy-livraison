package com.azylivraison.delivery.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExpressFuelCalculation(
        UUID requestId,
        String clientDisplayName,
        String pickupLabel,
        ExpressFuelCalculationStatus status,
        boolean returnToPickup,
        String failureReason,
        long totalDistanceMeters,
        long totalSuggestedFuelAriary,
        long totalFinalFuelAriary,
        long version,
        Instant calculatedAt,
        List<ExpressFuelLine> lines) {

    public ExpressFuelCalculation {
        lines = List.copyOf(lines == null ? List.of() : lines);
    }
}
