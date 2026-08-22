package com.azylivraison.optimization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.azylivraison.optimization.domain.OperationalPolicy;
import com.azylivraison.optimization.domain.OptimizationResult;
import com.azylivraison.optimization.domain.OptimizationResult.CourierRoute;
import com.azylivraison.optimization.domain.OptimizationResult.DataContext;
import com.azylivraison.optimization.domain.OptimizationResult.OptimizationStatus;
import com.azylivraison.optimization.domain.OptimizationResult.OptimizationSummary;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStop;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStopType;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryClient;
import com.azylivraison.planning.domain.DeliveryGroup;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.DeliveryPlan;
import com.azylivraison.planning.domain.DeliveryTimeWindow;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.PlanSettings;
import com.azylivraison.planning.domain.PlanningPattern;
import com.azylivraison.planning.domain.RoutingPreset;
import com.azylivraison.planning.domain.VehicleType;

import org.junit.jupiter.api.Test;

class OptimizationScenarioEvaluatorTest {

    private static final OffsetDateTime START = OffsetDateTime.of(
            2026, 8, 22, 8, 0, 0, 0, ZoneOffset.ofHours(3));
    private static final Location LOCATION = new Location("Lieu", "Lieu", -18.9, 47.5);
    private final OptimizationScenarioEvaluator evaluator = new OptimizationScenarioEvaluator();

    @Test
    void evaluatesUniqueDeliveriesAndRequestedWindowsFromThePlan() {
        DeliveryPackage late = deliveryPackage(
                "late",
                new DeliveryTimeWindow(START, START.plusHours(1)));
        DeliveryPackage exact = deliveryPackage(
                "exact",
                new DeliveryTimeWindow(START, START.plusMinutes(90)));
        DeliveryPackage withoutWindow = deliveryPackage("free", null);
        DeliveryPlan plan = plan(late, exact, withoutWindow);

        CourierRoute firstRoute = route(
                "courier-1",
                START.plusHours(2),
                1_000.4,
                2_000,
                List.of(
                        stop(1, RouteStopType.DELIVERY, null, "late", START.plusMinutes(65), 600),
                        stop(2, RouteStopType.DELIVERY, null, "late", START.plusMinutes(66), 20)));
        CourierRoute secondRoute = route(
                "courier-2",
                START.plusHours(3),
                2_000.4,
                2_000,
                List.of(
                        stop(1, RouteStopType.DELIVERY, null, "exact", START.plusMinutes(90), 500),
                        stop(2, RouteStopType.DELIVERY, null, "free", START.plusHours(2), 600)));

        var metrics = evaluator.evaluate(
                plan,
                result(firstRoute, secondRoute),
                OperationalPolicy.defaults());

        assertThat(metrics.packageCount()).isEqualTo(3);
        assertThat(metrics.assignedPackageCount()).isEqualTo(3);
        assertThat(metrics.onTimePackageCount()).isEqualTo(2);
        assertThat(metrics.latePackageCount()).isEqualTo(1);
        assertThat(metrics.totalLatenessSeconds()).isEqualTo(300);
        assertThat(metrics.maxLatenessSeconds()).isEqualTo(300);
        assertThat(metrics.totalDistanceMeters()).isEqualTo(3_001);
        assertThat(metrics.completedAt()).isEqualTo(START.plusHours(3));
        assertThat(metrics.usedCourierCount()).isEqualTo(2);
    }

    @Test
    void countsPickupServiceOncePerContiguousGroupVisitAndDecomposesWaiting() {
        DeliveryPlan plan = multiPlan(deliveryPackage("package-1", null), deliveryPackage("package-2", null));
        CourierRoute route = route(
                "courier-1",
                START.plusSeconds(4_500),
                4_200,
                4_500,
                List.of(
                        stop(5, RouteStopType.DELIVERY, null, "package-2", START.plusSeconds(4_200), 300),
                        stop(1, RouteStopType.PICKUP, "group-1", "package-1", START.plusSeconds(600), 600),
                        stop(4, RouteStopType.PICKUP, "group-1", "package-2", START.plusSeconds(3_900), 300),
                        stop(2, RouteStopType.PICKUP, "group-1", "package-2", START.plusSeconds(600), 0),
                        stop(3, RouteStopType.DELIVERY, null, "package-1", START.plusSeconds(3_000), 900)));

        var metrics = evaluator.evaluate(plan, result(route), OperationalPolicy.defaults());

        assertThat(metrics.drivingSeconds()).isEqualTo(2_100);
        assertThat(metrics.serviceSeconds()).isEqualTo(1_800);
        assertThat(metrics.waitingSeconds()).isEqualTo(600);
        assertThat(metrics.drivingSeconds() + metrics.serviceSeconds() + metrics.waitingSeconds())
                .isEqualTo(4_500);
    }

    @Test
    void derivesDrivingFromRouteDistanceAndCountsTheImplicitSinglePickup() {
        Courier courier = new Courier(
                "courier-1",
                "Livreur",
                VehicleType.MOTO,
                BigDecimal.TEN,
                new BigDecimal("36"),
                START,
                LOCATION);
        DeliveryPlan plan = plan(List.of(courier), deliveryPackage("package-1", null));
        CourierRoute route = route(
                "courier-1",
                START.plusSeconds(1_700),
                5_000,
                1_700,
                List.of(
                        stop(1, RouteStopType.DELIVERY, null, "package-1", START.plusSeconds(1_400), 100)));

        var metrics = evaluator.evaluate(plan, result(route), OperationalPolicy.defaults());

        assertThat(metrics.drivingSeconds()).isEqualTo(500);
        assertThat(metrics.serviceSeconds()).isEqualTo(900);
        assertThat(metrics.waitingSeconds()).isEqualTo(300);
    }

    @Test
    void comparesWindowInstantsInsteadOfTheirWrittenOffsets() {
        DeliveryPackage exactSameInstant = deliveryPackage(
                "same-instant",
                new DeliveryTimeWindow(START.minusHours(1), START.plusHours(1)));
        DeliveryPackage oneMinuteLate = deliveryPackage(
                "one-minute-late",
                new DeliveryTimeWindow(START.minusHours(1), START.plusHours(1)));
        DeliveryPlan plan = plan(exactSameInstant, oneMinuteLate);
        CourierRoute route = route(
                "courier-1",
                START.plusHours(2),
                1_000,
                2_000,
                List.of(
                        stop(1, RouteStopType.DELIVERY, null, "same-instant",
                                START.plusHours(1).withOffsetSameInstant(ZoneOffset.ofHours(1)), 400),
                        stop(2, RouteStopType.DELIVERY, null, "one-minute-late",
                                START.plusHours(1).plusMinutes(1).withOffsetSameInstant(ZoneOffset.ofHours(1)), 400)));

        var metrics = evaluator.evaluate(plan, result(route), OperationalPolicy.defaults());

        assertThat(metrics.onTimePackageCount()).isEqualTo(1);
        assertThat(metrics.latePackageCount()).isEqualTo(1);
        assertThat(metrics.totalLatenessSeconds()).isEqualTo(60);
    }

    @Test
    void reportsAnEmptyPhysicalExecutionWithoutTrustingTheResultSummary() {
        DeliveryPlan plan = plan(deliveryPackage("package-1", null));

        var metrics = evaluator.evaluate(plan, result(), OperationalPolicy.defaults());

        assertThat(metrics.packageCount()).isEqualTo(1);
        assertThat(metrics.assignedPackageCount()).isZero();
        assertThat(metrics.onTimePackageCount()).isZero();
        assertThat(metrics.latePackageCount()).isZero();
        assertThat(metrics.totalDistanceMeters()).isZero();
        assertThat(metrics.completedAt()).isNull();
        assertThat(metrics.usedCourierCount()).isZero();
        assertThat(metrics.drivingSeconds()).isZero();
        assertThat(metrics.waitingSeconds()).isZero();
        assertThat(metrics.serviceSeconds()).isZero();
    }

    private DeliveryPlan plan(DeliveryPackage... packages) {
        return plan(List.of(), packages);
    }

    private DeliveryPlan plan(List<Courier> couriers, DeliveryPackage... packages) {
        return new DeliveryPlan(
                "1.0",
                PlanningPattern.SINGLE_PICKUP_MULTI_DELIVERY,
                "plan-evaluator",
                "Plan évalué",
                START,
                "Indian/Antananarivo",
                new PlanSettings(false, RoutingPreset.MAIN_ROADS),
                LOCATION,
                couriers,
                List.of(packages));
    }

    private DeliveryPlan multiPlan(DeliveryPackage... packages) {
        DeliveryGroup group = new DeliveryGroup("group-1", "Retrait", LOCATION, List.of(packages));
        DeliveryClient client = new DeliveryClient("client-1", "Client", List.of(group));
        return new DeliveryPlan(
                "2.0",
                PlanningPattern.MULTI_CLIENT_MULTI_PICKUP_DELIVERY,
                "plan-evaluator",
                "Plan évalué",
                START,
                "Indian/Antananarivo",
                new PlanSettings(false, RoutingPreset.MAIN_ROADS),
                null,
                List.of(),
                List.of(),
                List.of(client));
    }

    private DeliveryPackage deliveryPackage(String id, DeliveryTimeWindow window) {
        return new DeliveryPackage(id, id, BigDecimal.ONE, null, LOCATION, window);
    }

    private OptimizationResult result(CourierRoute... routes) {
        return new OptimizationResult(
                "test",
                "plan-evaluator",
                START,
                OptimizationStatus.SUCCESS,
                "TEST",
                0,
                new DataContext("TEST", RoutingPreset.MAIN_ROADS, "TEST", "1"),
                new OptimizationSummary(99, 99, 0, 99, 99, BigDecimal.ZERO, 99_999, 99_999),
                List.of(routes),
                List.of(),
                List.of());
    }

    private CourierRoute route(
            String courierId,
            OffsetDateTime completedAt,
            double distanceMeters,
            double durationSeconds,
            List<RouteStop> stops) {
        return new CourierRoute(
                courierId,
                courierId,
                VehicleType.MOTO,
                BigDecimal.ZERO,
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                LOCATION,
                START,
                completedAt,
                distanceMeters,
                durationSeconds,
                stops);
    }

    private RouteStop stop(
            int order,
            RouteStopType type,
            String deliveryGroupId,
            String packageId,
            OffsetDateTime arrivalAt,
            double drivingSeconds) {
        return new RouteStop(
                order,
                type,
                "client-1",
                "Client",
                deliveryGroupId,
                deliveryGroupId,
                packageId,
                packageId,
                null,
                BigDecimal.ONE,
                LOCATION,
                arrivalAt,
                drivingSeconds,
                drivingSeconds,
                drivingSeconds,
                drivingSeconds);
    }
}
