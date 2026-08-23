package com.azylivraison.dispatch.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RegenerateDispatchRequest(
        @NotNull(message = "La version du plan est obligatoire.")
        @PositiveOrZero(message = "La version du plan est invalide.")
        Long expectedVersion,
        @NotBlank(message = "Indiquez la raison de la régénération.")
        @Size(max = 1000, message = "La raison ne peut pas dépasser 1000 caractères.")
        String reason,
        @NotEmpty(message = "Sélectionnez au moins un livreur.")
        @Size(max = 98, message = "La sélection contient trop de livreurs pour un seul dispatch.")
        List<@NotNull(message = "Un identifiant de livreur est invalide.") UUID> courierIds,
        @Valid DispatchLocationRequest hubOverride,
        boolean includeNewPending) {

    public RegenerateDispatchRequest {
        courierIds = courierIds == null ? List.of() : List.copyOf(courierIds);
    }
}
