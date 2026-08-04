package com.azylivraison.optimization.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.optimization")
public record OptimizationProperties(
        @NotNull Duration timeLimit,
        @Min(0) long vehicleActivationPenaltyMeters) {
}
