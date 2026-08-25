package com.azylivraison.dailyworkflow.api;

import com.azylivraison.delivery.api.ConfirmedLocationRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record CarryForwardRequest(
        @NotNull @Valid ConfirmedLocationRequest pickup,
        @NotNull @PositiveOrZero Long version) {
}
