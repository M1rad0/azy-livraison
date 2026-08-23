package com.azylivraison.dispatch.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ManualDispatchAssignmentRequest(
        @NotNull(message = "Le colis est obligatoire.") UUID packageId,
        @NotNull(message = "Le livreur de distribution est obligatoire.") UUID distributorCourierId,
        @Positive(message = "L'ordre de livraison doit commencer à 1.") int deliveryOrder) {
}
