package com.azylivraison.account.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateClientContactNumbersRequest(
        @NotNull @PositiveOrZero Long version,
        @NotNull @Size(min = 1, max = 20) List<@Valid Item> items) {

    public record Item(
            UUID id,
            @Size(max = 100) String label,
            @NotBlank @Size(max = 50) String number,
            @Positive Integer priority,
            boolean active) {
    }
}
