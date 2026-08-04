package com.azylivraison.routing.api;

import com.azylivraison.routing.domain.RoutingPoint;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RoutingPointRequest(
        @NotBlank(message = "L'identifiant du point est obligatoire.")
        @Size(max = 100, message = "L'identifiant du point ne peut pas dépasser 100 caractères.")
        String id,
        @NotNull(message = "La latitude est obligatoire.")
        @DecimalMin(value = "-90", message = "La latitude doit être comprise entre -90 et 90.")
        @DecimalMax(value = "90", message = "La latitude doit être comprise entre -90 et 90.")
        Double latitude,
        @NotNull(message = "La longitude est obligatoire.")
        @DecimalMin(value = "-180", message = "La longitude doit être comprise entre -180 et 180.")
        @DecimalMax(value = "180", message = "La longitude doit être comprise entre -180 et 180.")
        Double longitude) {

    RoutingPoint toDomain() {
        return new RoutingPoint(id.strip(), latitude, longitude);
    }
}
