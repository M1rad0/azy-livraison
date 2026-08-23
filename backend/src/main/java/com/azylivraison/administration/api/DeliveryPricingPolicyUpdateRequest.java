package com.azylivraison.administration.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record DeliveryPricingPolicyUpdateRequest(
        @NotNull(message = "La version de la tarification est obligatoire.")
        @PositiveOrZero(message = "La version ne peut pas etre negative.")
        Long version,
        @NotNull(message = "Le point central est obligatoire.") @Valid
        PricingCenterRequest center,
        @NotEmpty(message = "Configurez au moins une zone tarifaire.")
        @Size(max = 50, message = "La tarification ne peut pas contenir plus de 50 zones.")
        List<@NotNull(message = "Une zone tarifaire ne peut pas etre vide.") @Valid DeliveryPricingZoneRequest> zones) {

    public DeliveryPricingPolicyUpdateRequest {
        zones = zones == null
                ? null
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(zones));
    }
}
