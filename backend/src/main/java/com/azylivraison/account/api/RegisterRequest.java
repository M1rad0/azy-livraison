package com.azylivraison.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(min = 8, max = 200) String password,
        @NotBlank @Size(max = 150) String displayName,
        @NotBlank @Size(max = 50) String phone) {
}
