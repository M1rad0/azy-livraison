package com.azylivraison.geography.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.geocoding")
public record GeocodingProperties(
        @NotNull URI baseUrl,
        @NotBlank String userAgent,
        @NotBlank String countryCode,
        @NotBlank String acceptLanguage,
        @Min(1) @Max(10) int resultLimit,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull Duration cacheTtl) {
}
