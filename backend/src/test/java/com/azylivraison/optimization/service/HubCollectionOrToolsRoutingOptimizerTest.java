package com.azylivraison.optimization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import com.azylivraison.optimization.config.OptimizationProperties;
import com.azylivraison.optimization.domain.OperationalPolicy;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.VehicleType;

import org.junit.jupiter.api.Test;

class HubCollectionOrToolsRoutingOptimizerTest {

    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(
            2026, 8, 22, 6, 0, 0, 0, ZoneOffset.ofHours(3));
    private final HubCollectionOrToolsRoutingOptimizer optimizer = new HubCollectionOrToolsRoutingOptimizer(
            new OptimizationProperties(Duration.ofMillis(300), 1_000));

    @Test
    void startsAtTheRealOriginAndEndsAtTheHubAfterPickupService() {
        HubCollectionProblem problem = problem(
                List.of(courier("livreur-1", "10", DEPARTURE)),
                List.of(task("task-1", "retrait-1", "2")),
                1,
                new int[]{0},
                new int[]{2},
                new int[]{0, 1, 2},
                matrix(
                        row(0, 2_500, 1_000),
                        row(2_500, 0, 2_000),
                        row(1_000, 2_000, 0)));

        HubCollectionSolvedOptimization result = optimizer.solve(problem);

        assertThat(result.droppedTaskIndexes()).isEmpty();
        assertThat(result.latestHubArrivalOffsetSeconds()).isEqualTo(900);
        assertThat(result.routes()).singleElement().satisfies(route -> {
            assertThat(route.courierIndex()).isZero();
            assertThat(route.departureOffsetSeconds()).isZero();
            assertThat(route.hubArrivalOffsetSeconds()).isEqualTo(900);
            assertThat(route.distanceMeters()).isEqualTo(3_000);
            assertThat(route.hubLegDistanceMeters()).isEqualTo(2_000);
            assertThat(route.visits()).singleElement().satisfies(visit -> {
                assertThat(visit.arrivalOffsetSeconds()).isEqualTo(100);
                assertThat(visit.serviceDurationSeconds())
                        .isEqualTo(OperationalPolicy.DEFAULT_PICKUP_SERVICE_DURATION.toSeconds());
            });
        });
    }

    @Test
    void preservesEachCourierAbsoluteDepartureTimeAndIgnoresAnUnusedEarlierCourier() {
        HubCollectionProblem problem = problem(
                List.of(
                        courier("trop-petit", "1", DEPARTURE),
                        courier("livreur-2", "10", DEPARTURE.plusHours(1))),
                List.of(task("task-1", "retrait-1", "2")),
                2,
                new int[]{0, 1},
                new int[]{3},
                new int[]{0, 1, 2, 3},
                matrix(
                        row(0, 2_000, 2_000, 2_000),
                        row(2_000, 0, 2_500, 1_000),
                        row(2_000, 2_500, 0, 2_000),
                        row(2_000, 1_000, 2_000, 0)));

        HubCollectionSolvedOptimization result = optimizer.solve(problem);

        assertThat(result.routes()).singleElement().satisfies(route -> {
            assertThat(route.courierIndex()).isEqualTo(1);
            assertThat(route.departureOffsetSeconds()).isEqualTo(3_600);
            assertThat(route.hubArrivalOffsetSeconds()).isEqualTo(4_500);
        });
        assertThat(result.latestHubArrivalOffsetSeconds()).isEqualTo(4_500);
    }

    @Test
    void chargesPickupServiceOnceForConsecutiveSplitPackagesAtTheSameWithdrawal() {
        HubCollectionProblem problem = problem(
                List.of(courier("livreur-1", "10", DEPARTURE)),
                List.of(
                        splitTask("task-1", "retrait-lourd", "2"),
                        splitTask("task-2", "retrait-lourd", "2")),
                1,
                new int[]{0},
                new int[]{2, 3},
                new int[]{0, 1, 2, 2},
                matrix(
                        row(0, 2_500, 1_000),
                        row(2_500, 0, 2_000),
                        row(1_000, 2_000, 0)));

        HubCollectionSolvedOptimization result = optimizer.solve(problem);

        assertThat(result.droppedTaskIndexes()).isEmpty();
        assertThat(result.latestHubArrivalOffsetSeconds()).isEqualTo(900);
        assertThat(result.routes()).singleElement().satisfies(route -> {
            assertThat(route.distanceMeters()).isEqualTo(3_000);
            assertThat(route.visits()).hasSize(2);
            assertThat(route.visits()).extracting(
                    HubCollectionSolvedOptimization.SolvedVisit::serviceDurationSeconds)
                    .containsExactlyInAnyOrder(0L, OperationalPolicy.DEFAULT_PICKUP_SERVICE_DURATION.toSeconds());
        });
    }

    @Test
    void neverExceedsCapacityBecauseNothingIsUnloadedBeforeTheHub() {
        HubCollectionProblem problem = problem(
                List.of(courier("livreur-1", "5", DEPARTURE)),
                List.of(
                        task("task-1", "retrait-1", "4"),
                        task("task-2", "retrait-2", "4")),
                1,
                new int[]{0},
                new int[]{2, 3},
                new int[]{0, 1, 2, 3},
                matrix(
                        row(0, 1_000, 1_000, 1_000),
                        row(1_000, 0, 1_000, 1_000),
                        row(1_000, 1_000, 0, 100),
                        row(1_000, 1_000, 100, 0)));

        HubCollectionSolvedOptimization result = optimizer.solve(problem);

        assertThat(result.droppedTaskIndexes()).hasSize(1);
        assertThat(result.routes()).singleElement().satisfies(route -> assertThat(route.visits()).hasSize(1));
    }

    @Test
    void minimizesLatestHubArrivalBeforeTotalDistance() {
        HubCollectionProblem problem = problem(
                List.of(
                        courier("livreur-1", "10", DEPARTURE),
                        courier("livreur-2", "10", DEPARTURE)),
                List.of(
                        task("task-1", "retrait-1", "1"),
                        task("task-2", "retrait-2", "1")),
                2,
                new int[]{0, 1},
                new int[]{3, 4},
                new int[]{0, 1, 2, 3, 4},
                matrix(
                        row(0, 0, 1_000, 1_000, 1_000),
                        row(0, 0, 1_000, 1_000, 1_000),
                        row(1_000, 1_000, 0, 1_000, 1_000),
                        row(1_000, 1_000, 1_000, 0, 100),
                        row(1_000, 1_000, 1_000, 100, 0)));

        HubCollectionSolvedOptimization result = optimizer.solve(problem);

        assertThat(result.droppedTaskIndexes()).isEmpty();
        assertThat(result.routes()).hasSize(2);
        assertThat(result.latestHubArrivalOffsetSeconds()).isEqualTo(800);
    }

    @Test
    void prefersAReachableLongCollectionOverDroppingItsPackage() {
        HubCollectionProblem problem = problem(
                List.of(courier("livreur-1", "10", DEPARTURE)),
                List.of(task("task-1", "retrait-lointain", "1")),
                1,
                new int[]{0},
                new int[]{2},
                new int[]{0, 1, 2},
                matrix(
                        row(0, 200_000, 100_000),
                        row(200_000, 0, 100_000),
                        row(100_000, 100_000, 0)));

        HubCollectionSolvedOptimization result = optimizer.solve(problem);

        assertThat(result.droppedTaskIndexes()).isEmpty();
        assertThat(result.routes()).hasSize(1);
    }

    @Test
    void dropsAWithdrawalThatHasNoRoadConnection() {
        HubCollectionProblem problem = problem(
                List.of(courier("livreur-1", "10", DEPARTURE)),
                List.of(task("task-1", "retrait-inaccessible", "1")),
                1,
                new int[]{0},
                new int[]{2},
                new int[]{0, 1, 2},
                List.of(
                        Arrays.asList(0d, 1_000d, null),
                        Arrays.asList(1_000d, 0d, null),
                        Arrays.asList(null, null, 0d)));

        HubCollectionSolvedOptimization result = optimizer.solve(problem);

        assertThat(result.routes()).isEmpty();
        assertThat(result.droppedTaskIndexes()).containsExactly(0);
    }

    private static HubCollectionProblem problem(
            List<Courier> couriers,
            List<HubCollectionTask> tasks,
            int hubNode,
            int[] courierStartNodes,
            int[] taskNodes,
            int[] matrixPointByNode,
            List<List<Double>> matrix) {
        return new HubCollectionProblem(
                couriers,
                tasks,
                hubNode,
                courierStartNodes,
                taskNodes,
                matrixPointByNode,
                matrix);
    }

    private static Courier courier(String id, String capacity, OffsetDateTime departureAt) {
        return new Courier(
                id,
                id,
                VehicleType.MOTO,
                new BigDecimal(capacity),
                new BigDecimal("36"),
                departureAt,
                location("Origine " + id));
    }

    private static HubCollectionTask task(String id, String groupId, String weight) {
        return task(id, groupId, weight, HubCollectionTask.Kind.WHOLE_WITHDRAWAL);
    }

    private static HubCollectionTask splitTask(String id, String groupId, String weight) {
        return task(id, groupId, weight, HubCollectionTask.Kind.SPLIT_PACKAGE);
    }

    private static HubCollectionTask task(
            String id,
            String groupId,
            String weight,
            HubCollectionTask.Kind kind) {
        BigDecimal packageWeight = new BigDecimal(weight);
        DeliveryPackage deliveryPackage = new DeliveryPackage(
                "package-" + id,
                "Colis " + id,
                packageWeight,
                null,
                location("Destination " + id),
                null);
        return new HubCollectionTask(
                id,
                "client-1",
                "Client",
                groupId,
                groupId,
                location(groupId),
                List.of(deliveryPackage),
                packageWeight,
                kind);
    }

    private static Location location(String label) {
        return new Location(label, label, -18.9, 47.5);
    }

    private static List<Double> row(double... values) {
        return java.util.Arrays.stream(values).boxed().toList();
    }

    @SafeVarargs
    private static List<List<Double>> matrix(List<Double>... rows) {
        return List.of(rows);
    }
}
