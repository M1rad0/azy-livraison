package com.azylivraison.administration.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.azylivraison.administration.domain.ConfirmedLocation;

public record ConfirmedLocationRequest(
        @Size(max = 200, message = "Le libellé du lieu ne peut pas dépasser 200 caractères.")
        String label,
        @Size(max = 500, message = "L'adresse recherchée ne peut pas dépasser 500 caractères.")
        String destinationQuery,
        @NotNull(message = "La latitude confirmée est obligatoire.")
        @DecimalMin(value = "-90", message = "La latitude doit être comprise entre -90 et 90.")
        @DecimalMax(value = "90", message = "La latitude doit être comprise entre -90 et 90.")
        BigDecimal latitude,
        @NotNull(message = "La longitude confirmée est obligatoire.")
        @DecimalMin(value = "-180", message = "La longitude doit être comprise entre -180 et 180.")
        @DecimalMax(value = "180", message = "La longitude doit être comprise entre -180 et 180.")
        BigDecimal longitude) {

    ConfirmedLocation toDomain() {
        return new ConfirmedLocation(
                trimToNull(label),
                trimToNull(destinationQuery),
                latitude.doubleValue(),
                longitude.doubleValue());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
