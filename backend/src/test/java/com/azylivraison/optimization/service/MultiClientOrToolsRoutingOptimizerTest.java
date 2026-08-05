package com.azylivraison.optimization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.azylivraison.optimization.config.OptimizationProperties;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStopType;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.VehicleType;

import org.junit.jupiter.api.Test;

class MultiClientOrToolsRoutingOptimizerTest {

    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(
            2026, 8, 5, 6, 0, 0, 0, ZoneOffset.ofHours(3));
    private final MultiClientOrToolsRoutingOptimizer optimizer = new MultiClientOrToolsRoutingOptimizer(
            new OptimizationProperties(Duration.ofSeconds(2), 1_000));

    @Test
    void startsAtTheCourierOriginAndThenVisitsThePickup() {
        Location courierOrigin = location("Alasora", -18.9553235, 47.5674821);
        Location pickup = location("Ampasampito", -18.8957014, 47.545794);
        Location destination = location("Destination", -18.9385813, 47.5281625);
        Courier courier = new Courier(
                "livreur-001",
                "Omni Man",
                VehicleType.MOTO,
                new BigDecimal("30"),
                new BigDecimal("25"),
                DEPARTURE,
                courierOrigin);
        DeliveryPackage deliveryPackage = new DeliveryPackage(
                "colis-001", "Colis", new BigDecimal("1.5"), null, destination, null);
        MultiClientOptimizationJob job = new MultiClientOptimizationJob(
                "client-001", "Client", "course-001", "Course", pickup, deliveryPackage);
        MultiClientOptimizationProblem problem = new MultiClientOptimizationProblem(
                DEPARTURE,
                List.of(courier),
                List.of(job),
                new int[]{0},
                new int[]{1},
                new int[]{2},
                new int[]{0, 1, 2},
                matrix(
                        row(0, 10_000, 12_000),
                        row(10_000, 0, 2_000),
                        row(12_000, 2_000, 0)));

        MultiClientSolvedOptimization result = optimizer.solve(problem);

        assertThat(result.droppedJobIndexes()).isEmpty();
        assertThat(result.routes()).singleElement().satisfies(route -> {
            assertThat(route.departureOffsetSeconds()).isZero();
            assertThat(route.distanceMeters()).isEqualTo(12_000);
            assertThat(route.visits())
                    .extracting(MultiClientSolvedOptimization.SolvedVisit::type)
                    .containsExactly(RouteStopType.PICKUP, RouteStopType.DELIVERY);
            assertThat(route.visits().get(0)).satisfies(visit -> {
                assertThat(visit.distanceFromPreviousMeters()).isEqualTo(10_000);
                assertThat(visit.arrivalOffsetSeconds()).isEqualTo(1_440);
            });
        });
    }

    @Test
    void supportsCouriersWithAndWithoutAKnownOriginInTheSamePlan() {
        Location courierOrigin = location("Alasora", -18.9553235, 47.5674821);
        Location pickup = location("Ampasampito", -18.8957014, 47.545794);
        Location destination = location("Destination", -18.9385813, 47.5281625);
        Courier courierWithOrigin = new Courier(
                "livreur-001", "Omni Man", VehicleType.MOTO,
                new BigDecimal("30"), new BigDecimal("25"), DEPARTURE, courierOrigin);
        Courier courierWithoutOrigin = new Courier(
                "livreur-002", "Soa", VehicleType.MOTO,
                new BigDecimal("0.5"), new BigDecimal("25"), null, null);
        DeliveryPackage deliveryPackage = new DeliveryPackage(
                "colis-001", "Colis", new BigDecimal("1.5"), null, destination, null);
        MultiClientOptimizationJob job = new MultiClientOptimizationJob(
                "client-001", "Client", "course-001", "Course", pickup, deliveryPackage);
        MultiClientOptimizationProblem problem = new MultiClientOptimizationProblem(
                DEPARTURE,
                List.of(courierWithOrigin, courierWithoutOrigin),
                List.of(job),
                new int[]{0, -1},
                new int[]{1},
                new int[]{2},
                new int[]{0, 1, 2},
                matrix(
                        row(0, 10_000, 12_000),
                        row(10_000, 0, 2_000),
                        row(12_000, 2_000, 0)));

        MultiClientSolvedOptimization result = optimizer.solve(problem);

        assertThat(result.droppedJobIndexes()).isEmpty();
        assertThat(result.routes()).singleElement().satisfies(route -> {
            assertThat(route.courierIndex()).isZero();
            assertThat(route.visits())
                    .extracting(MultiClientSolvedOptimization.SolvedVisit::type)
                    .containsExactly(RouteStopType.PICKUP, RouteStopType.DELIVERY);
        });
    }

    private static Location location(String label, double latitude, double longitude) {
        return new Location(label, label, latitude, longitude);
    }

    private static List<Double> row(double... values) {
        return java.util.Arrays.stream(values).boxed().toList();
    }

    @SafeVarargs
    private static List<List<Double>> matrix(List<Double>... rows) {
        return List.of(rows);
    }
}
