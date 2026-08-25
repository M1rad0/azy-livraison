package com.azylivraison.dispatch.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateDispatchRequest(
        @NotNull(message = "Choisissez la date du dispatch.")
        LocalDate serviceDate,
        @NotEmpty(message = "Sélectionnez au moins un livreur.")
        @Size(max = 98, message = "La sélection contient trop de livreurs pour un seul dispatch.")
        List<@NotNull(message = "Un identifiant de livreur est invalide.") UUID> courierIds,
        @Valid
        DispatchLocationRequest hubOverride) {

    public CreateDispatchRequest {
        courierIds = courierIds == null ? List.of() : List.copyOf(courierIds);
    }
}
