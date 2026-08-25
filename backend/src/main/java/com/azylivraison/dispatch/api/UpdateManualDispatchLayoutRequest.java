package com.azylivraison.dispatch.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateManualDispatchLayoutRequest(
        @NotNull(message = "La version du plan est obligatoire.")
        @PositiveOrZero(message = "La version du plan est invalide.")
        Long expectedVersion,
        @NotBlank(message = "Indiquez la raison de la modification.")
        @Size(max = 1000, message = "La raison ne peut pas dépasser 1000 caractères.")
        String reason,
        @NotEmpty(message = "La répartition manuelle est obligatoire.")
        List<@Valid ManualDispatchAssignmentRequest> assignments) {

    public UpdateManualDispatchLayoutRequest {
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
    }
}
