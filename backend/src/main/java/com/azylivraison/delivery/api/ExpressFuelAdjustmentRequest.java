package com.azylivraison.delivery.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ExpressFuelAdjustmentRequest(
        @NotNull UUID courierId,
        @PositiveOrZero Long finalDistanceMeters,
        @PositiveOrZero Long finalFuelAmountAriary,
        @NotNull @Size(min = 1, max = 1000) String reason) {
}
