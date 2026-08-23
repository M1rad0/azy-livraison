package com.azylivraison.administration.api;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import com.azylivraison.planning.domain.RoutingPreset;

public record OperationalSettingsUpdateRequest(
        @NotNull(message = "Le nombre maximal de colis par jour est obligatoire.")
        @Positive(message = "Le nombre maximal de colis par jour doit être positif.")
        Integer dailyPackageLimit,
        @Valid
        ConfirmedLocationRequest defaultHub,
        @NotNull(message = "Le poids par défaut d'un colis est obligatoire.")
        @Positive(message = "Le poids par défaut d'un colis doit être positif.")
        BigDecimal defaultPackageWeightKg,
        @NotNull(message = "La durée d'un retrait est obligatoire.")
        @Positive(message = "La durée d'un retrait doit être positive.")
        Integer pickupServiceMinutes,
        @NotNull(message = "La durée au point de ralliement est obligatoire.")
        @Positive(message = "La durée au point de ralliement doit être positive.")
        Integer hubServiceMinutes,
        @NotNull(message = "La durée d'une livraison est obligatoire.")
        @Positive(message = "La durée d'une livraison doit être positive.")
        Integer deliveryServiceMinutes,
        @NotNull(message = "La tolérance d'avance est obligatoire.")
        @PositiveOrZero(message = "La tolérance d'avance ne peut pas être négative.")
        Integer earlyToleranceMinutes,
        @NotNull(message = "La tolérance de retard est obligatoire.")
        @PositiveOrZero(message = "La tolérance de retard ne peut pas être négative.")
        Integer lateToleranceMinutes,
        @NotNull(message = "La limite de temps de l'optimisation est obligatoire.")
        @Positive(message = "La limite de temps de l'optimisation doit être positive.")
        @Max(value = 1200, message = "La limite de temps de l'optimisation ne peut pas dépasser 1200 secondes.")
        Integer optimizationTimeLimitSeconds,
        @NotNull(message = "Le profil routier est obligatoire.")
        RoutingPreset routingPreset,
        @PositiveOrZero(message = "La version ne peut pas être négative.")
        Long version) {
}
