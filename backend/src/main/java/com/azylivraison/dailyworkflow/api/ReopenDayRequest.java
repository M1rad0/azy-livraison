package com.azylivraison.dailyworkflow.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ReopenDayRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 1000) String reason) {
}
