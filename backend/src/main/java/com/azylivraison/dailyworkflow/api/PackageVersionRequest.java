package com.azylivraison.dailyworkflow.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PackageVersionRequest(
        @NotNull @PositiveOrZero Long version) {
}
