package com.azylivraison.optimization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import com.azylivraison.optimization.config.OptimizationProperties;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.DeliveryTimeWindow;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.VehicleType;

import org.junit.jupiter.api.Test;

class HubDistributionOrToolsRoutingOptimizerTest {

    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(
            2026, 8, 22, 8, 15, 0, 0, ZoneOffset.ofHours(3));
    private final HubDistributionOrToolsRoutingOptimizer optimizer = new HubDistributionOrToolsRoutingOptimizer(
            new OptimizationProperties(Duration.ofSeconds(2), 1_000));

    @Test
    void startsTogetherAtTheHubAndIncludesDeliveryServiceInCompletion() {
        HubDistributionProblem problem = problem(
                List.of(job("p1", 1, null), job("p2", 1, null)),
                courier("c1", 10),
                matrix(
                        row(0, 1_000, 4_000),
                        row(1_000, 0, 1_000),
                        row(4_000, 1_000, 0)));

        HubDistributionSolvedOptimization solved = optimizer.solve(problem);

        assertThat(solved.droppedJobIndexes()).isEmpty();
        assertThat(solved.routes()).singleElement().satisfies(route -> {
            assertThat(route.departureOffsetSeconds()).isZero();
            assertThat(route.visits()).hasSize(2);
            assertThat(route.serviceSeconds()).isEqualTo(600);
            assertThat(route.completionOffsetSeconds())
                    .isEqualTo(route.visits().get(1).serviceCompletedOffsetSeconds());
            assertThat(route.visits().get(1).serviceStartedOffsetSeconds()
                    - route.visits().get(0).serviceStartedOffsetSeconds()).isGreaterThanOrEqualTo(300);
        });
    }

    @Test
    void waitsForAnEarlyWindowAndAllowsThirtyMinutesOfLateness() {
        DeliveryTimeWindow futureWindow = new DeliveryTimeWindow(
                DEPARTURE.plusMinutes(20), DEPARTURE.plusMinutes(30));
        DeliveryTimeWindow toleratedLateWindow = new DeliveryTimeWindow(
                DEPARTURE.minusMinutes(10), DEPARTURE.minusMinutes(5));
        HubDistributionProblem problem = problem(
                List.of(job("future", 1, futureWindow), job("late", 1, toleratedLateWindow)),
                courier("c1", 10),
                matrix(
                        row(0, 100, 100),
                        row(100, 0, 100),
                        row(100, 100, 0)));

        HubDistributionSolvedOptimization solved = optimizer.solve(problem);

        assertThat(solved.droppedJobIndexes()).isEmpty();
        assertThat(solved.routes()).singleElement().satisfies(route -> {
            assertThat(route.waitingSeconds()).isPositive();
            assertThat(route.visits()).anySatisfy(visit -> assertThat(visit.latenessSeconds()).isPositive());
        });
    }

    @Test
    void enforcesCapacityAfterRedistribution() {
        HubDistributionProblem problem = problem(
                List.of(job("p1", 6, null), job("p2", 6, null)),
                courier("c1", 10),
                matrix(
                        row(0, 100, 200),
                        row(100, 0, 100),
                        row(200, 100, 0)));

        HubDistributionSolvedOptimization solved = optimizer.solve(problem);

        assertThat(solved.droppedJobIndexes()).hasSize(1);
        assertThat(solved.routes()).singleElement().satisfies(route -> assertThat(route.visits()).hasSize(1));
    }

    @Test
    void includesTheApproachToTheHubWhenChoosingADistributor() {
        List<MultiClientOptimizationJob> jobs = List.of(job("p1", 1, null));
        Courier nearby = courier("nearby", 10);
        Courier farAway = courier("far-away", 10);
        HubDistributionProblem problem = new HubDistributionProblem(
                DEPARTURE,
                List.of(nearby, farAway),
                jobs,
                0,
                new int[]{1},
                matrix(row(0, 100), row(100, 0)),
                new long[]{100, 10_000});

        HubDistributionSolvedOptimization solved = optimizer.solve(problem);

        assertThat(solved.droppedJobIndexes()).isEmpty();
        assertThat(solved.routes()).singleElement()
                .satisfies(route -> assertThat(problem.couriers().get(route.courierIndex()).id()).isEqualTo("nearby"));
    }

    @Test
    void usesEveryCapableCourierAndBalancesPackageCounts() {
        HubDistributionOrToolsRoutingOptimizer productionTimeOptimizer =
                new HubDistributionOrToolsRoutingOptimizer(
                        new OptimizationProperties(Duration.ofSeconds(5), 1_000));
        List<Courier> couriers = List.of(
                courier("c1", 10),
                courier("c2", 10),
                courier("c3", 10));
        List<MultiClientOptimizationJob> jobs = List.of(
                job("p1", 1, null),
                job("p2", 1, null),
                job("p3", 1, null),
                job("p4", 1, null),
                job("p5", 1, null),
                job("p6", 1, null),
                job("p7", 1, null));
        HubDistributionProblem problem = problem(couriers, jobs, uniformMatrix(8, 1_000, 100),
                new long[]{0, 0, 0});

        HubDistributionSolvedOptimization solved = productionTimeOptimizer.solve(problem);

        assertThat(solved.droppedJobIndexes()).isEmpty();
        assertThat(solved.routes()).hasSize(3);
        assertThat(solved.routes().stream().map(route -> route.visits().size()).sorted().toList())
                .containsExactly(2, 2, 3);
    }

    @Test
    void usesAtMostOneCourierPerPackageAndKeepsApproachAsTieBreaker() {
        List<Courier> couriers = List.of(
                courier("nearby", 10),
                courier("second", 10),
                courier("far-away", 10));
        List<MultiClientOptimizationJob> jobs = List.of(
                job("p1", 1, null),
                job("p2", 1, null));
        HubDistributionProblem problem = problem(couriers, jobs, uniformMatrix(3, 1_000, 100),
                new long[]{100, 200, 10_000});

        HubDistributionSolvedOptimization solved = optimizer.solve(problem);

        assertThat(solved.droppedJobIndexes()).isEmpty();
        assertThat(solved.routes()).hasSize(2);
        assertThat(solved.routes().stream()
                .map(route -> problem.couriers().get(route.courierIndex()).id())
                .toList())
                .containsExactlyInAnyOrder("nearby", "second");
    }

    @Test
    void ignoresACourierWhoCannotCarryAnyPackage() {
        List<Courier> couriers = List.of(
                courier("c1", 10),
                courier("c2", 10),
                courier("too-small", 0.5));
        List<MultiClientOptimizationJob> jobs = List.of(
                job("p1", 1, null),
                job("p2", 1, null),
                job("p3", 1, null));
        HubDistributionProblem problem = problem(couriers, jobs, uniformMatrix(4, 1_000, 100),
                new long[]{0, 0, 0});

        HubDistributionSolvedOptimization solved = optimizer.solve(problem);

        assertThat(solved.droppedJobIndexes()).isEmpty();
        assertThat(solved.routes()).hasSize(2);
        assertThat(solved.routes().stream().map(route -> route.visits().size()).sorted().toList())
                .containsExactly(1, 2);
        assertThat(solved.routes().stream()
                .map(route -> problem.couriers().get(route.courierIndex()).id())
                .toList())
                .doesNotContain("too-small");
    }

    @Test
    void doesNotForceACourierWhoCannotMeetAnyHardWindow() {
        HubDistributionOrToolsRoutingOptimizer strictOptimizer = new HubDistributionOrToolsRoutingOptimizer(
                new OptimizationProperties(
                        Duration.ofSeconds(2),
                        1_000,
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(15),
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO));
        DeliveryTimeWindow tightWindow = new DeliveryTimeWindow(
                DEPARTURE,
                DEPARTURE.plusMinutes(1));
        List<Courier> couriers = List.of(
                courier("fast", 10, 36),
                courier("too-slow", 10, 1));
        List<MultiClientOptimizationJob> jobs = List.of(
                job("p1", 1, tightWindow),
                job("p2", 1, tightWindow));
        HubDistributionProblem problem = problem(couriers, jobs, uniformMatrix(3, 100, 100),
                new long[]{0, 0});

        HubDistributionSolvedOptimization solved = strictOptimizer.solve(problem);

        assertThat(solved.droppedJobIndexes()).isEmpty();
        assertThat(solved.routes()).singleElement().satisfies(route -> {
            assertThat(problem.couriers().get(route.courierIndex()).id()).isEqualTo("fast");
            assertThat(route.visits()).hasSize(2);
        });
    }

    private HubDistributionProblem problem(
            List<MultiClientOptimizationJob> jobs,
            Courier courier,
            List<List<Double>> distances) {
        int[] nodes = new int[jobs.size()];
        for (int index = 0; index < jobs.size(); index++) nodes[index] = index + 1;
        return new HubDistributionProblem(DEPARTURE, List.of(courier), jobs, 0, nodes, distances);
    }

    private HubDistributionProblem problem(
            List<Courier> couriers,
            List<MultiClientOptimizationJob> jobs,
            List<List<Double>> distances,
            long[] activationCosts) {
        int[] nodes = new int[jobs.size()];
        for (int index = 0; index < jobs.size(); index++) nodes[index] = index + 1;
        return new HubDistributionProblem(
                DEPARTURE,
                couriers,
                jobs,
                0,
                nodes,
                distances,
                activationCosts);
    }

    private MultiClientOptimizationJob job(String id, double weight, DeliveryTimeWindow window) {
        Location pickup = location("Retrait", -18.90, 47.52);
        DeliveryPackage deliveryPackage = new DeliveryPackage(
                id, id, BigDecimal.valueOf(weight), null, location(id, -18.91, 47.53), window);
        return new MultiClientOptimizationJob("client", "Client", "retrait", "Retrait", pickup, deliveryPackage);
    }

    private Courier courier(String id, double capacity) {
        return courier(id, capacity, 36);
    }

    private Courier courier(String id, double capacity, double speed) {
        return new Courier(
                id, id, VehicleType.MOTO, BigDecimal.valueOf(capacity), BigDecimal.valueOf(speed),
                DEPARTURE.minusMinutes(15), location("Origine", -18.89, 47.51));
    }

    private Location location(String label, double latitude, double longitude) {
        return new Location(label, label, latitude, longitude);
    }

    private static List<Double> row(double... values) {
        return Arrays.stream(values).boxed().toList();
    }

    private static List<List<Double>> uniformMatrix(int size, double hubDistance, double interDeliveryDistance) {
        Double[][] values = new Double[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                values[row][column] = row == column
                        ? 0
                        : row == 0 || column == 0 ? hubDistance : interDeliveryDistance;
            }
        }
        return Arrays.stream(values).map(Arrays::asList).toList();
    }

    @SafeVarargs
    private static List<List<Double>> matrix(List<Double>... rows) {
        return List.of(rows);
    }
}
