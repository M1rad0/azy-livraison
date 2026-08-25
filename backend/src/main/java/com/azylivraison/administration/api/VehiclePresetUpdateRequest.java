package com.azylivraison.administration.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record VehiclePresetUpdateRequest(
        @NotNull(message = "La capacité maximale est obligatoire.")
        @Positive(message = "La capacité maximale doit être positive.")
        BigDecimal maxWeightKg,
        @NotNull(message = "La vitesse moyenne est obligatoire.")
        @Positive(message = "La vitesse moyenne doit être positive.")
        BigDecimal averageSpeedKmh,
        @PositiveOrZero(message = "La version ne peut pas être négative.")
        Long version) {
}
