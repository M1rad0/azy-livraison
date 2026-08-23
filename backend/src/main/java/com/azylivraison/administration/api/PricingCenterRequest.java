package com.azylivraison.administration.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.azylivraison.pricing.domain.PricingCenter;

public record PricingCenterRequest(
        @NotBlank(message = "Le libelle du point central est obligatoire.")
        @Size(max = 200, message = "Le libelle du point central ne peut pas depasser 200 caracteres.")
        String label,
        @Size(max = 500, message = "L'adresse recherchee ne peut pas depasser 500 caracteres.")
        String destinationQuery,
        @NotNull(message = "La latitude confirmee est obligatoire.")
        @DecimalMin(value = "-90", message = "La latitude doit etre comprise entre -90 et 90.")
        @DecimalMax(value = "90", message = "La latitude doit etre comprise entre -90 et 90.")
        Double latitude,
        @NotNull(message = "La longitude confirmee est obligatoire.")
        @DecimalMin(value = "-180", message = "La longitude doit etre comprise entre -180 et 180.")
        @DecimalMax(value = "180", message = "La longitude doit etre comprise entre -180 et 180.")
        Double longitude) {

    PricingCenter toDomain() {
        return new PricingCenter(
                label == null ? null : label.trim(),
                destinationQuery == null || destinationQuery.isBlank() ? null : destinationQuery.trim(),
                latitude == null ? Double.NaN : latitude,
                longitude == null ? Double.NaN : longitude);
    }
}
