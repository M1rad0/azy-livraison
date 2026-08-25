package com.azylivraison.dispatch.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CancelDispatchRequest(
        @NotNull(message = "La version du plan est obligatoire.")
        @PositiveOrZero(message = "La version du plan est invalide.")
        Long expectedVersion,
        @NotBlank(message = "Indiquez la raison de l'annulation.")
        @Size(max = 1000, message = "La raison ne peut pas dépasser 1000 caractères.")
        String reason) {
}
