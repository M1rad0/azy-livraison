package com.azylivraison.delivery.api;

import java.time.LocalDate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RescheduleDeliveryPackageRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull LocalDate requestedDate,
        @Valid ConfirmedLocationRequest pickup) {
}
