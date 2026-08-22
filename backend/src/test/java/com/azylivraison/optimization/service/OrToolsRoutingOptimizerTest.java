package com.azylivraison.optimization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.azylivraison.optimization.config.OptimizationProperties;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.DeliveryTimeWindow;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.VehicleType;

import org.junit.jupiter.api.Test;

class OrToolsRoutingOptimizerTest {

    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(
            2026, 8, 5, 8, 0, 0, 0, ZoneOffset.ofHours(3));
    private static final Location DEPOT = location("Dépôt", -18.9089, 47.5212);
    private final OrToolsRoutingOptimizer optimizer = new OrToolsRoutingOptimizer(
            new OptimizationProperties(Duration.ofSeconds(2), 1_000));

    @Test
    void groupsNearbyPackagesOnOneCourierInsteadOfArtificiallyBalancingRoutes() {
        List<Courier> couriers = List.of(
                courier("courier-1", "Soa", "10", "30"),
                courier("courier-2", "Tiana", "10", "30"));
        List<DeliveryPackage> packages = List.of(
                deliveryPackage("package-1", "Colis A", "2", null),
                deliveryPackage("package-2", "Colis B", "2", null));

        SolvedOptimization result = optimizer.solve(problem(
                couriers,
                packages,
                new int[]{0, 0},
                new int[]{1, 2},
                matrix(
                        row(0, 1_000, 1_100),
                        row(1_000, 0, 100),
                        row(1_100, 100, 0))));

        assertThat(result.droppedPackageIndexes()).isEmpty();
        assertThat(result.routes()).singleElement()
                .satisfies(route -> assertThat(route.visits())
                        .extracting(SolvedOptimization.SolvedVisit::packageIndex)
                        .containsExactlyInAnyOrder(0, 1));
    }

    @Test
    void neverExceedsCourierCapacityAndDropsTheUnassignablePackage() {
        List<Courier> couriers = List.of(courier("courier-1", "Soa", "5", "30"));
        List<DeliveryPackage> packages = List.of(
                deliveryPackage("package-1", "Colis A", "4", null),
                deliveryPackage("package-2", "Colis B", "4", null));

        SolvedOptimization result = optimizer.solve(problem(
                couriers,
                packages,
                new int[]{0},
                new int[]{1, 2},
                matrix(
                        row(0, 1_000, 1_100),
                        row(1_000, 0, 100),
                        row(1_100, 100, 0))));

        assertThat(result.droppedPackageIndexes()).hasSize(1);
        assertThat(result.routes()).singleElement()
                .satisfies(route -> assertThat(route.visits()).hasSize(1));
    }

    @Test
    void ordersStopsToRespectDeliveryTimeWindows() {
        DeliveryTimeWindow laterWindow = new DeliveryTimeWindow(
                DEPARTURE.plusMinutes(20), DEPARTURE.plusMinutes(30));
        DeliveryTimeWindow earlyWindow = new DeliveryTimeWindow(
                DEPARTURE.plusMinutes(4), DEPARTURE.plusMinutes(10));
        List<Courier> couriers = List.of(courier("courier-1", "Soa", "10", "36"));
        List<DeliveryPackage> packages = List.of(
                deliveryPackage("package-later", "Plus tard", "1", laterWindow),
                deliveryPackage("package-early", "En premier", "1", earlyWindow));

        SolvedOptimization result = optimizer.solve(problem(
                couriers,
                packages,
                new int[]{0},
                new int[]{1, 2},
                matrix(
                        row(0, 600, 3_000),
                        row(600, 0, 3_000),
                        row(3_000, 3_000, 0))));

        assertThat(result.droppedPackageIndexes()).isEmpty();
        assertThat(result.routes()).singleElement().satisfies(route -> {
            assertThat(route.visits())
                    .extracting(SolvedOptimization.SolvedVisit::packageIndex)
                    .containsExactly(1, 0);
            assertThat(route.visits().get(0).arrivalOffsetSeconds()).isEqualTo(900L);
            assertThat(route.visits().get(1).arrivalOffsetSeconds()).isEqualTo(1_500L);
        });
    }

    @Test
    void includesTheFinalLegWhenThePlanRequiresAReturnToDepot() {
        List<Courier> couriers = List.of(courier("courier-1", "Soa", "10", "30"));
        List<DeliveryPackage> packages = List.of(deliveryPackage("package-1", "Colis A", "2", null));
        OptimizationProblem problem = new OptimizationProblem(
                DEPARTURE,
                true,
                0,
                couriers,
                packages,
                new int[]{0},
                new double[]{0},
                new int[]{1},
                matrix(row(0, 1_000), row(1_500, 0)));

        SolvedOptimization result = optimizer.solve(problem);

        assertThat(result.droppedPackageIndexes()).isEmpty();
        assertThat(result.routes()).singleElement()
                .satisfies(route -> assertThat(route.distanceMeters()).isEqualTo(2_500));
    }

    @Test
    void requiresACourierToReachTheDepotBeforeTheFirstDelivery() {
        Courier courier = new Courier(
                "courier-1",
                "Soa",
                VehicleType.MOTO,
                new BigDecimal("10"),
                new BigDecimal("36"),
                DEPARTURE,
                location("Départ livreur", -18.92, 47.50));
        List<DeliveryPackage> packages = List.of(deliveryPackage("package-1", "Colis A", "2", null));

        SolvedOptimization result = optimizer.solve(problem(
                List.of(courier),
                packages,
                new int[]{1},
                new int[]{2},
                matrix(
                        row(0, 3_000, 1_000),
                        row(3_000, 0, 100),
                        row(1_000, 100, 0))));

        assertThat(result.droppedPackageIndexes()).isEmpty();
        assertThat(result.routes()).singleElement().satisfies(route -> {
            assertThat(route.departureOffsetSeconds()).isZero();
            assertThat(route.completionOffsetSeconds()).isBetween(1_300L, 1_310L);
            assertThat(route.distanceMeters()).isEqualTo(4_000);
            assertThat(route.visits()).singleElement().satisfies(visit -> {
                assertThat(visit.arrivalOffsetSeconds()).isBetween(1_000L, 1_010L);
                assertThat(visit.distanceFromPreviousMeters()).isEqualTo(1_000);
                assertThat(visit.cumulativeDistanceMeters()).isEqualTo(4_000);
            });
        });
    }

    private OptimizationProblem problem(
            List<Courier> couriers,
            List<DeliveryPackage> packages,
            int[] courierStartNodes,
            int[] packageNodes,
            List<List<Double>> distances) {
        return new OptimizationProblem(
                DEPARTURE,
                false,
                0,
                couriers,
                packages,
                courierStartNodes,
                java.util.stream.IntStream.range(0, courierStartNodes.length)
                        .mapToDouble(index -> courierStartNodes[index] == 0
                                ? 0
                                : distances.get(courierStartNodes[index]).get(0))
                        .toArray(),
                packageNodes,
                distances);
    }

    private static Courier courier(
            String id,
            String name,
            String capacityKg,
            String speedKmh) {
        return new Courier(
                id,
                name,
                VehicleType.MOTO,
                new BigDecimal(capacityKg),
                new BigDecimal(speedKmh),
                null,
                null);
    }

    private static DeliveryPackage deliveryPackage(
            String id,
            String label,
            String weightKg,
            DeliveryTimeWindow window) {
        return new DeliveryPackage(
                id,
                label,
                new BigDecimal(weightKg),
                null,
                DEPOT,
                window);
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
