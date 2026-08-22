package com.azylivraison.account.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateClientProfileRequest(
        @NotBlank @Size(max = 150) String displayName,
        @NotBlank @Size(max = 50) String phone,
        @Valid DefaultPickupRequest defaultPickup) {
}
