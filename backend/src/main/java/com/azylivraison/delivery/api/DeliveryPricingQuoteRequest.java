package com.azylivraison.delivery.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record DeliveryPricingQuoteRequest(
        @NotNull(message = "La destination confirmee est obligatoire.") @Valid
        ConfirmedLocationRequest destination) {
}
