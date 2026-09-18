package com.azylivraison.delivery.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateExpressFuelRequest(
        @NotNull @PositiveOrZero Long version,
        @NotEmpty List<@Valid ExpressFuelAdjustmentRequest> lines) {

    public UpdateExpressFuelRequest {
        lines = lines == null ? null : List.copyOf(lines);
    }
}
