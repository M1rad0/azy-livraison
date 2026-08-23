package com.azylivraison.dailyworkflow.api;

import com.azylivraison.delivery.domain.DeliveryPackageOutcome;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record OutcomeUpdateRequest(
        @NotNull DeliveryPackageOutcome outcome,
        @PositiveOrZero Long actualCollectedAriary,
        @Size(max = 1000) String note,
        @NotNull @PositiveOrZero Long version) {
}
