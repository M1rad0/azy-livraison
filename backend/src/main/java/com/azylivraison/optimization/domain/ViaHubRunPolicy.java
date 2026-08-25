package com.azylivraison.optimization.domain;

import java.time.Duration;
import java.util.Objects;

/** Immutable settings snapshot applied to one via-hub dispatch calculation. */
public record ViaHubRunPolicy(
        OperationalPolicy operationalPolicy,
        Duration optimizationTimeLimit) {

    public ViaHubRunPolicy {
        operationalPolicy = Objects.requireNonNull(operationalPolicy, "operationalPolicy must not be null");
        optimizationTimeLimit = Objects.requireNonNull(
                optimizationTimeLimit,
                "optimizationTimeLimit must not be null");
        if (optimizationTimeLimit.isZero() || optimizationTimeLimit.isNegative()) {
            throw new IllegalArgumentException("optimizationTimeLimit must be strictly positive");
        }
    }
}
