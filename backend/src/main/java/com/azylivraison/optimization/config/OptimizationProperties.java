package com.azylivraison.optimization.config;

import java.time.Duration;

import com.azylivraison.optimization.domain.OperationalPolicy;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.optimization")
public record OptimizationProperties(
        @NotNull Duration timeLimit,
        @Min(0) long vehicleActivationPenaltyMeters,
        @NotNull Duration pickupServiceDuration,
        @NotNull Duration hubTransferDuration,
        @NotNull Duration deliveryServiceDuration,
        @NotNull Duration deliveryWindowEarlyTolerance,
        @NotNull Duration deliveryWindowLateTolerance) {

    @ConstructorBinding
    public OptimizationProperties {
        new OperationalPolicy(
                pickupServiceDuration,
                hubTransferDuration,
                deliveryServiceDuration,
                deliveryWindowEarlyTolerance,
                deliveryWindowLateTolerance);
    }

    public OptimizationProperties(
            Duration timeLimit,
            long vehicleActivationPenaltyMeters) {
        this(
                timeLimit,
                vehicleActivationPenaltyMeters,
                OperationalPolicy.DEFAULT_PICKUP_SERVICE_DURATION,
                OperationalPolicy.DEFAULT_HUB_TRANSFER_DURATION,
                OperationalPolicy.DEFAULT_DELIVERY_SERVICE_DURATION,
                OperationalPolicy.DEFAULT_DELIVERY_WINDOW_EARLY_TOLERANCE,
                OperationalPolicy.DEFAULT_DELIVERY_WINDOW_LATE_TOLERANCE);
    }

    public OperationalPolicy operationalPolicy() {
        return new OperationalPolicy(
                pickupServiceDuration,
                hubTransferDuration,
                deliveryServiceDuration,
                deliveryWindowEarlyTolerance,
                deliveryWindowLateTolerance);
    }
}
