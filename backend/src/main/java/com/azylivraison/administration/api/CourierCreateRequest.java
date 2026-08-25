package com.azylivraison.administration.api;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.azylivraison.planning.domain.VehicleType;

public record CourierCreateRequest(
        UUID accountId,
        @NotBlank(message = "Le nom du livreur est obligatoire.")
        @Size(max = 150, message = "Le nom du livreur ne peut pas dépasser 150 caractères.")
        String name,
        @Size(max = 100, message = "Le contact ne peut pas dépasser 100 caractères.")
        String contact,
        @Size(max = 50)
        String phoneNumber,
        @Valid
        CourierPayoutDestinationRequest payoutDestination,
        @NotNull(message = "Le type de véhicule est obligatoire.")
        VehicleType vehicleType,
        @Positive(message = "La capacité maximale doit être positive.")
        BigDecimal maxWeightKg,
        @Positive(message = "La vitesse moyenne doit être positive.")
        BigDecimal averageSpeedKmh,
        @NotNull(message = "L'heure de départ habituelle est obligatoire.")
        LocalTime defaultDepartureTime,
        @NotNull(message = "Le point de départ habituel est obligatoire.")
        @Valid
        ConfirmedLocationRequest departureLocation) {
}
