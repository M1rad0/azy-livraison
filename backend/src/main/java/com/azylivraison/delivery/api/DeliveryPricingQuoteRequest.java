package com.azylivraison.delivery.api;

import java.math.BigDecimal;
import java.time.LocalTime;

import com.azylivraison.delivery.domain.DeliveryRequestKind;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DeliveryPricingQuoteRequest(
        @NotNull(message = "La destination confirmee est obligatoire.") @Valid
        ConfirmedLocationRequest destination,
        DeliveryRequestKind requestKind,
        @Positive(message = "Le poids doit etre positif.") BigDecimal weightKg,
        LocalTime desiredDeliveryTime) {

    public DeliveryPricingQuoteRequest {
        requestKind = requestKind == null ? DeliveryRequestKind.NORMAL : requestKind;
    }
}
