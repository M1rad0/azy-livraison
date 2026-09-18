package com.azylivraison.optimization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.azylivraison.optimization.config.OptimizationProperties;

import org.junit.jupiter.api.Test;

class ExpressRouteOptimizerTest {

    private final ExpressRouteOptimizer optimizer = new ExpressRouteOptimizer(
            new OptimizationProperties(Duration.ofSeconds(1), 0));

    @Test
    void optimizesAClosedRouteFromAndBackToPickup() {
        var result = optimizer.optimize(List.of(
                List.of(0d, 10d, 50d),
                List.of(50d, 0d, 10d),
                List.of(10d, 50d, 0d)), true);

        assertThat(result.destinationIndexes()).containsExactly(0, 1);
        assertThat(result.distanceMeters()).isEqualTo(30);
    }

    @Test
    void leavesTheLastDestinationOpenWhenReturnIsDisabled() {
        var result = optimizer.optimize(List.of(
                List.of(0d, 10d, 50d),
                List.of(50d, 0d, 10d),
                List.of(10d, 50d, 0d)), false);

        assertThat(result.destinationIndexes()).containsExactly(0, 1);
        assertThat(result.distanceMeters()).isEqualTo(20);
    }
}
