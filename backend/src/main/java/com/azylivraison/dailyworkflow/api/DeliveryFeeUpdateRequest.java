package com.azylivraison.dailyworkflow.api;

import com.azylivraison.dailyworkflow.domain.DeliveryFeeMode;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record DeliveryFeeUpdateRequest(
        @NotNull DeliveryFeeMode mode,
        @PositiveOrZero Long manualFeeAriary,
        @Size(max = 1000) String reason,
        @PositiveOrZero long version) {
}
