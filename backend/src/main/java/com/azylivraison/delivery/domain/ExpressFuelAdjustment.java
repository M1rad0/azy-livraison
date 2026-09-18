package com.azylivraison.delivery.domain;

import java.util.UUID;

public record ExpressFuelAdjustment(
        UUID courierId,
        Long finalDistanceMeters,
        Long finalFuelAmountAriary,
        String reason) {
}
