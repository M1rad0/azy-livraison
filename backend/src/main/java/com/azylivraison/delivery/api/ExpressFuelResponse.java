package com.azylivraison.delivery.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.domain.ExpressFuelCalculation;
import com.azylivraison.delivery.domain.ExpressFuelCalculationStatus;
import com.azylivraison.delivery.domain.ExpressFuelLine;

public record ExpressFuelResponse(
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
        List<Line> lines) {

    public ExpressFuelResponse {
        lines = List.copyOf(lines);
    }

    public static ExpressFuelResponse from(ExpressFuelCalculation calculation) {
        return new ExpressFuelResponse(
                calculation.requestId(), calculation.clientDisplayName(), calculation.pickupLabel(),
                calculation.status(), calculation.returnToPickup(), calculation.failureReason(),
                calculation.totalDistanceMeters(), calculation.totalSuggestedFuelAriary(),
                calculation.totalFinalFuelAriary(), calculation.version(), calculation.calculatedAt(),
                calculation.lines().stream().map(Line::from).toList());
    }

    public record Line(
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

        private static Line from(ExpressFuelLine line) {
            return new Line(
                    line.courierId(), line.courierName(), line.courierContact(), line.packageIds(),
                    line.repositionDistanceMeters(), line.automaticDistanceMeters(), line.finalDistanceMeters(),
                    line.fuelReimbursementEnabled(), line.fuelEfficiencyKmPerLiter(),
                    line.fuelPricePerLiterAriary(), line.suggestedFuelAriary(),
                    line.finalFuelAriary(), line.overrideReason(), line.manual());
        }
    }
}
