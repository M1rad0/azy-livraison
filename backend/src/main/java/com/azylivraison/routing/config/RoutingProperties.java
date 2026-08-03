package com.azylivraison.routing.config;

import java.net.URI;
import java.time.Duration;

import com.azylivraison.planning.domain.RoutingPreset;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.routing")
public record RoutingProperties(
        @NotNull URI baseUrl,
        @NotBlank String providerProfile,
        @NotNull RoutingPreset preset,
        @Min(2) @Max(1000) int maxMatrixPoints,
        @Min(2) @Max(1000) int maxRoutePoints,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
}
