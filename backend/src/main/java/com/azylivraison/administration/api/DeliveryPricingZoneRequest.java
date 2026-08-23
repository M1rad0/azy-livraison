package com.azylivraison.administration.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.azylivraison.pricing.domain.PricingZone;

public record DeliveryPricingZoneRequest(
        UUID id,
        @NotBlank(message = "Le nom de la zone est obligatoire.")
        @Size(max = 100, message = "Le nom de la zone ne peut pas depasser 100 caracteres.")
        String name,
        @NotNull(message = "Le rayon maximal est obligatoire.")
        @Positive(message = "Le rayon maximal doit etre positif.")
        Long maxDistanceMeters,
        @NotNull(message = "Le prix de livraison est obligatoire.")
        @PositiveOrZero(message = "Le prix de livraison ne peut pas etre negatif.")
        Long deliveryFeeAriary) {

    PricingZone toDomain() {
        return new PricingZone(
                id,
                name,
                maxDistanceMeters == null ? 0 : maxDistanceMeters,
                deliveryFeeAriary == null ? -1 : deliveryFeeAriary);
    }
}
