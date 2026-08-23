package com.azylivraison.account.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateClientPayoutDestinationsRequest(
        @NotNull @PositiveOrZero Long version,
        @NotNull @Size(max = 20) List<@Valid Item> items) {

    public record Item(
            UUID id,
            @NotBlank @Size(max = 80) String operator,
            @NotBlank @Size(max = 150) String accountHolder,
            @NotBlank @Size(max = 100) String number,
            @Positive Integer priority,
            boolean active) {
    }
}
