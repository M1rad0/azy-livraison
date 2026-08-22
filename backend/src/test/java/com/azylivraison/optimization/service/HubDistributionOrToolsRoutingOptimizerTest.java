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

    private HubDistributionProblem problem(
            List<MultiClientOptimizationJob> jobs,
            Courier courier,
            List<List<Double>> distances) {
        int[] nodes = new int[jobs.size()];
        for (int index = 0; index < jobs.size(); index++) nodes[index] = index + 1;
        return new HubDistributionProblem(DEPARTURE, List.of(courier), jobs, 0, nodes, distances);
    }

    private MultiClientOptimizationJob job(String id, double weight, DeliveryTimeWindow window) {
        Location pickup = location("Retrait", -18.90, 47.52);
        DeliveryPackage deliveryPackage = new DeliveryPackage(
                id, id, BigDecimal.valueOf(weight), null, location(id, -18.91, 47.53), window);
        return new MultiClientOptimizationJob("client", "Client", "retrait", "Retrait", pickup, deliveryPackage);
    }

    private Courier courier(String id, double capacity) {
        return new Courier(
                id, id, VehicleType.MOTO, BigDecimal.valueOf(capacity), BigDecimal.valueOf(36),
                DEPARTURE.minusMinutes(15), location("Origine", -18.89, 47.51));
    }

    private Location location(String label, double latitude, double longitude) {
        return new Location(label, label, latitude, longitude);
    }

    private static List<Double> row(double... values) {
        return Arrays.stream(values).boxed().toList();
    }

    @SafeVarargs
    private static List<List<Double>> matrix(List<Double>... rows) {
        return List.of(rows);
    }
}
