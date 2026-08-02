package com.azylivraison.account.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(
        @NotBlank String issuer,
        @NotBlank @Size(min = 32) String accessSecret,
        @Positive long accessTtlMinutes,
        @NotBlank @Size(min = 32) String refreshSecret,
        @Positive long refreshTtlDays,
        boolean cookieSecure,
        @NotBlank String initialUsername,
        @NotBlank @Size(min = 12) String initialPassword) {
}
