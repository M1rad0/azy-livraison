package com.azylivraison.delivery.api;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.FutureOrPresent;

public record CreateDeliveryRequest(
        @NotNull
        @FutureOrPresent(message = "La date de livraison ne peut pas être passée.")
        LocalDate requestedDate,
        @NotNull @Valid ConfirmedLocationRequest pickup,
        Boolean saveAsDefaultPickup,
        @NotEmpty @Size(max = 500) List<@Valid CreateDeliveryPackageRequest> packages) {

    public CreateDeliveryRequest {
        saveAsDefaultPickup = Boolean.TRUE.equals(saveAsDefaultPickup);
        packages = packages == null ? null : List.copyOf(packages);
    }
}
