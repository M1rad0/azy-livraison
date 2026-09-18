package com.azylivraison.delivery.api;

import java.time.LocalDate;
import java.util.List;

import com.azylivraison.delivery.domain.DeliveryRequestKind;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CreateDeliveryRequest(
        LocalDate requestedDate,
        DeliveryRequestKind requestKind,
        @jakarta.validation.constraints.NotNull @Valid ConfirmedLocationRequest pickup,
        Boolean saveAsDefaultPickup,
        @NotEmpty @Size(max = 500) List<@Valid CreateDeliveryPackageRequest> packages) {

    public CreateDeliveryRequest {
        requestKind = requestKind == null ? DeliveryRequestKind.NORMAL : requestKind;
        saveAsDefaultPickup = Boolean.TRUE.equals(saveAsDefaultPickup);
        packages = packages == null ? null : List.copyOf(packages);
    }
}
