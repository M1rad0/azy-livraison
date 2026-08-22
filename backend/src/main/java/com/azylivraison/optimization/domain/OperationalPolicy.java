package com.azylivraison.optimization.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Operational durations and delivery-window tolerances used by an optimization run.
 *
 * <p>The policy is kept outside the planning file contract for now. It can therefore
 * be snapshotted in an optimization result without pretending that historical 1.0
 * and 2.0 files already carried these settings.</p>
 */
public record OperationalPolicy(
        Duration pickupServiceDuration,
        Duration hubTransferDuration,
        Duration deliveryServiceDuration,
        Duration deliveryWindowEarlyTolerance,
        Duration deliveryWindowLateTolerance) {

    public static final Duration DEFAULT_PICKUP_SERVICE_DURATION = Duration.ofMinutes(10);
    public static final Duration DEFAULT_HUB_TRANSFER_DURATION = Duration.ofMinutes(15);
    public static final Duration DEFAULT_DELIVERY_SERVICE_DURATION = Duration.ofMinutes(5);
    public static final Duration DEFAULT_DELIVERY_WINDOW_EARLY_TOLERANCE = Duration.ZERO;
    public static final Duration DEFAULT_DELIVERY_WINDOW_LATE_TOLERANCE = Duration.ofMinutes(30);

    public OperationalPolicy {
        pickupServiceDuration = requireNonNegative(pickupServiceDuration, "pickupServiceDuration");
        hubTransferDuration = requireNonNegative(hubTransferDuration, "hubTransferDuration");
        deliveryServiceDuration = requireNonNegative(deliveryServiceDuration, "deliveryServiceDuration");
        deliveryWindowEarlyTolerance = requireNonNegative(
                deliveryWindowEarlyTolerance,
                "deliveryWindowEarlyTolerance");
        deliveryWindowLateTolerance = requireNonNegative(
                deliveryWindowLateTolerance,
                "deliveryWindowLateTolerance");
    }

    public static OperationalPolicy defaults() {
        return new OperationalPolicy(
                DEFAULT_PICKUP_SERVICE_DURATION,
                DEFAULT_HUB_TRANSFER_DURATION,
                DEFAULT_DELIVERY_SERVICE_DURATION,
                DEFAULT_DELIVERY_WINDOW_EARLY_TOLERANCE,
                DEFAULT_DELIVERY_WINDOW_LATE_TOLERANCE);
    }

    private static Duration requireNonNegative(Duration value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }
}
