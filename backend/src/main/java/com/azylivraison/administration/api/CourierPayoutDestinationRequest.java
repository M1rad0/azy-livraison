package com.azylivraison.administration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.azylivraison.administration.domain.CourierPayoutDestination;

public record CourierPayoutDestinationRequest(
        @NotBlank @Size(max = 80) String operator,
        @NotBlank @Size(max = 150) String accountHolder,
        @NotBlank @Size(max = 100) String number) {

    CourierPayoutDestination toDomain() {
        return new CourierPayoutDestination(operator, accountHolder, number);
    }
}
