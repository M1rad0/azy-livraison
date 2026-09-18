package com.azylivraison.delivery.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConfirmExpressDeliveryRequest(
        @NotEmpty @Size(max = 50) List<@NotNull UUID> courierIds,
        Boolean returnToPickup,
        @NotNull Long version) {

    public ConfirmExpressDeliveryRequest {
        courierIds = courierIds == null ? null : List.copyOf(courierIds);
    }
}
