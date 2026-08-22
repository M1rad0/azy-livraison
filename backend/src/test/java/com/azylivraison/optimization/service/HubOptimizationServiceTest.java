package com.azylivraison.optimization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import com.azylivraison.optimization.config.OptimizationProperties;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.HubRole;
import com.azylivraison.optimization.domain.OptimizationResult.UnassignedReason;
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
import com.azylivraison.routing.service.RoutingMatrixResult;
import com.azylivraison.routing.service.RoutingService;

import org.junit.jupiter.api.Test;

class HubOptimizationServiceTest {

    private static final OffsetDateTime START = OffsetDateTime.of(
            2026, 8, 22, 6, 0, 0, 0, ZoneOffset.ofHours(3));

    @Test
    void composesCollectionMeetingRedistributionAndDistributionWithoutDoubleCountingDistance() {
        RoutingService routingService = mock(RoutingService.class);
        when(routingService.matrix(anyList())).thenReturn(
                matrix(
                        row(0, 1_000, 1_500),
                        row(1_000, 0, 1_000),
                        row(1_500, 1_000, 0)),
                matrix(
                        row(0, 1_000),
                        row(1_000, 0)));
        OptimizationProperties properties = new OptimizationProperties(Duration.ofSeconds(2), 1_000);
        HubOptimizationService service = new HubOptimizationService(
                routingService,
                new HubCollectionTaskFactory(),
                new HubCollectionOrToolsRoutingOptimizer(properties),
                new HubDistributionOrToolsRoutingOptimizer(properties),
                properties);

        HubOptimizationOutcome outcome = service.optimize(plan());

        assertThat(outcome.result().summary().assignedPackageCount()).isEqualTo(1);
        assertThat(outcome.result().summary().unassignedPackageCount()).isZero();
        assertThat(outcome.result().summary().totalDistanceMeters()).isEqualTo(3_000);
        assertThat(outcome.metrics().assignedPackageCount()).isEqualTo(1);
        assertThat(outcome.hubExecution().collectionDistanceMeters()).isEqualTo(2_000);
        assertThat(outcome.hubExecution().distributorApproachDistanceMeters()).isZero();
        assertThat(outcome.hubExecution().distributionDistanceMeters()).isEqualTo(1_000);
        assertThat(outcome.hubExecution().redistributionCompletedAt())
                .isEqualTo(outcome.hubExecution().meetingAt().plusMinutes(15));
        assertThat(outcome.hubExecution().manifests()).singleElement().satisfies(manifest -> {
            assertThat(manifest.roles()).containsExactly(HubRole.COLLECTION, HubRole.DISTRIBUTION);
            assertThat(manifest.collectedPackages()).extracting(item -> item.packageId()).containsExactly("package-1");
            assertThat(manifest.keptPackages()).extracting(item -> item.packageId()).containsExactly("package-1");
            assertThat(manifest.transferredOutPackages()).isEmpty();
            assertThat(manifest.receivedPackages()).isEmpty();
            assertThat(manifest.deliveredPackages()).extracting(item -> item.packageId()).containsExactly("package-1");
        });
        assertThat(outcome.hubExecution().packageTransfers()).singleElement()
                .satisfies(transfer -> assertThat(transfer.transferred()).isFalse());
    }

    @Test
    void leavesAnUnresolvedDestinationUnassignedWithoutCollectingItAndOptimizesTheOthers() {
        RoutingService routingService = mock(RoutingService.class);
        when(routingService.matrix(anyList())).thenReturn(
                matrix(
                        row(0, 1_000, 1_500),
                        row(1_000, 0, 1_000),
                        row(1_500, 1_000, 0)),
                matrix(
                        row(0, 1_000),
                        row(1_000, 0)));
        OptimizationProperties properties = new OptimizationProperties(Duration.ofSeconds(2), 1_000);
        HubOptimizationService service = new HubOptimizationService(
                routingService,
                new HubCollectionTaskFactory(),
                new HubCollectionOrToolsRoutingOptimizer(properties),
                new HubDistributionOrToolsRoutingOptimizer(properties),
                properties);

        HubOptimizationOutcome outcome = service.optimize(planWithUnresolvedDestination());

        assertThat(outcome.result().summary().packageCount()).isEqualTo(2);
        assertThat(outcome.result().summary().assignedPackageCount()).isEqualTo(1);
        assertThat(outcome.result().summary().unassignedPackageCount()).isEqualTo(1);
        assertThat(outcome.result().unassignedPackages()).singleElement().satisfies(unassigned -> {
            assertThat(unassigned.packageId()).isEqualTo("package-unresolved");
            assertThat(unassigned.reason()).isEqualTo(UnassignedReason.DESTINATION_UNRESOLVED);
        });
        assertThat(outcome.hubExecution().manifests()).singleElement().satisfies(manifest -> {
            assertThat(manifest.collectedPackages())
                    .extracting(item -> item.packageId())
                    .containsExactly("package-1");
            assertThat(manifest.deliveredPackages())
                    .extracting(item -> item.packageId())
                    .containsExactly("package-1");
        });
        assertThat(outcome.hubExecution().packageTransfers())
                .extracting(transfer -> transfer.deliveryPackage().packageId())
                .containsExactly("package-1");
    }

    @Test
    void reportsAnUnresolvedDestinationWithoutCallingTheRoutingProviderWhenNothingIsEligible() {
        RoutingService routingService = mock(RoutingService.class);
        OptimizationProperties properties = new OptimizationProperties(Duration.ofSeconds(2), 1_000);
        HubOptimizationService service = new HubOptimizationService(
                routingService,
                new HubCollectionTaskFactory(),
                new HubCollectionOrToolsRoutingOptimizer(properties),
                new HubDistributionOrToolsRoutingOptimizer(properties),
                properties);

        HubOptimizationOutcome outcome = service.optimize(planWithOnlyUnresolvedDestination());

        assertThat(outcome.result().summary().assignedPackageCount()).isZero();
        assertThat(outcome.result().unassignedPackages()).singleElement().satisfies(unassigned -> {
            assertThat(unassigned.packageId()).isEqualTo("package-unresolved");
            assertThat(unassigned.reason()).isEqualTo(UnassignedReason.DESTINATION_UNRESOLVED);
        });
        assertThat(outcome.hubExecution().packageTransfers()).isEmpty();
        verifyNoInteractions(routingService);
    }

    @Test
    void synchronizesTwoCouriersAndTransfersAParcelWithoutDoubleCountingTheirApproaches() {
        RoutingService routingService = mock(RoutingService.class);
        when(routingService.matrix(anyList())).thenReturn(
                matrix(
                        row(0, 500, 100, 200),
                        row(500, 0, 10_000, 500),
                        row(100, 10_000, 0, 100),
                        row(200, 500, 100, 0)),
                matrix(
                        row(0, 500, 100),
                        row(500, 0, 600),
                        row(100, 600, 0)));
        OptimizationProperties properties = new OptimizationProperties(Duration.ofSeconds(2), 1_000);
        HubOptimizationService service = new HubOptimizationService(
                routingService,
                new HubCollectionTaskFactory(),
                new HubCollectionOrToolsRoutingOptimizer(properties),
                new HubDistributionOrToolsRoutingOptimizer(properties),
                properties);

        DeliveryPlan plan = twoCourierPlan();
        HubOptimizationOutcome outcome = service.optimize(plan);

        assertThat(plan.couriers())
                .extracting(Courier::departureLocation)
                .doesNotHaveDuplicates();
        assertThat(outcome.result().summary().assignedPackageCount()).isEqualTo(2);
        assertThat(outcome.result().summary().unassignedPackageCount()).isZero();
        assertThat(outcome.hubExecution().meetingAt()).isEqualTo(START.plusSeconds(800));
        assertThat(outcome.hubExecution().redistributionCompletedAt())
                .isEqualTo(START.plusSeconds(800).plusMinutes(15));

        assertThat(outcome.hubExecution().collectionDistanceMeters()).isEqualTo(200);
        assertThat(outcome.hubExecution().distributorApproachDistanceMeters()).isEqualTo(500);
        assertThat(outcome.hubExecution().distributionDistanceMeters()).isEqualTo(600);
        assertThat(outcome.result().summary().totalDistanceMeters()).isEqualTo(1_300);

        assertThat(outcome.hubExecution().packageTransfers())
                .anySatisfy(transfer -> {
                    assertThat(transfer.deliveryPackage().packageId()).isEqualTo("package-fast");
                    assertThat(transfer.collectorCourierId()).isEqualTo("courier-collector");
                    assertThat(transfer.distributorCourierId()).isEqualTo("courier-distributor");
                    assertThat(transfer.transferred()).isTrue();
                    assertThat(transfer.arrivedAtHub()).isEqualTo(outcome.hubExecution().meetingAt());
                    assertThat(transfer.departedHub()).isEqualTo(outcome.hubExecution().redistributionCompletedAt());
                });
        assertThat(outcome.hubExecution().manifests()).hasSize(2).anySatisfy(manifest -> {
            assertThat(manifest.courierId()).isEqualTo("courier-distributor");
            assertThat(manifest.arrivedAt()).isEqualTo(START.plusSeconds(50));
            assertThat(manifest.waitingSeconds()).isEqualTo(750);
            assertThat(manifest.roles()).containsExactly(HubRole.DISTRIBUTION);
            assertThat(manifest.receivedPackages())
                    .extracting(item -> item.packageId())
                    .containsExactly("package-fast");
        });
    }

    @Test
    void recalculatesCollectionWhenALateWithdrawalWouldLoseMoreUrgentDeliveries() {
        RoutingService routingService = mock(RoutingService.class);
        when(routingService.matrix(anyList())).thenReturn(
                matrix(
                        row(0, 100, 18_000, 200),
                        row(100, 0, 18_000, 100),
                        row(18_000, 18_000, 0, 100),
                        row(200, 100, 100, 0)),
                matrix(
                        row(0, 100, 100, 100),
                        row(100, 0, 100, 100),
                        row(100, 100, 0, 100),
                        row(100, 100, 100, 0)),
                matrix(
                        row(0, 100, 100),
                        row(100, 0, 100),
                        row(100, 100, 0)));
        OptimizationProperties properties = new OptimizationProperties(Duration.ofMillis(400), 1_000);
        HubOptimizationService service = new HubOptimizationService(
                routingService,
                new HubCollectionTaskFactory(),
                new HubCollectionOrToolsRoutingOptimizer(properties),
                new HubDistributionOrToolsRoutingOptimizer(properties),
                properties);

        HubOptimizationOutcome outcome = service.optimize(feedbackPlan());

        assertThat(outcome.result().summary().assignedPackageCount()).isEqualTo(2);
        assertThat(outcome.metrics().onTimePackageCount()).isEqualTo(2);
        assertThat(outcome.hubExecution().meetingAt()).isEqualTo(START.plusSeconds(620));
        assertThat(outcome.result().unassignedPackages()).singleElement().satisfies(unassigned -> {
            assertThat(unassigned.packageId()).isEqualTo("package-distant");
            assertThat(unassigned.reason()).isEqualTo(UnassignedReason.NO_FEASIBLE_ASSIGNMENT);
        });
    }

    private DeliveryPlan plan() {
        Location origin = location("Origine", -18.90, 47.50);
        Location pickup = location("Retrait", -18.91, 47.51);
        Location hub = location("Siège", -18.92, 47.52);
        Location destination = location("Destination", -18.93, 47.53);
        DeliveryPackage deliveryPackage = new DeliveryPackage(
                "package-1", "Colis", new BigDecimal("5"), null, destination, null);
        DeliveryGroup group = new DeliveryGroup("group-1", "Retrait", pickup, List.of(deliveryPackage));
        DeliveryClient client = new DeliveryClient("client-1", "Client", List.of(group));
        Courier courier = new Courier(
                "courier-1", "Soa", VehicleType.MOTO,
                new BigDecimal("30"), new BigDecimal("36"), START, origin);
        return new DeliveryPlan(
                "2.1",
                PlanningPattern.MULTI_CLIENT_MULTI_PICKUP_DELIVERY,
                "plan-hub",
                "Plan siège",
                START,
                "Indian/Antananarivo",
                new PlanSettings(false, RoutingPreset.MAIN_ROADS),
                null,
                hub,
                List.of(courier),
                List.of(),
                List.of(client));
    }

    private DeliveryPlan planWithUnresolvedDestination() {
        DeliveryPlan base = plan();
        DeliveryGroup originalGroup = base.clients().getFirst().deliveryGroups().getFirst();
        DeliveryPackage unresolved = unresolvedPackage();
        DeliveryGroup mixedGroup = new DeliveryGroup(
                originalGroup.id(),
                originalGroup.name(),
                originalGroup.pickup(),
                List.of(originalGroup.packages().getFirst(), unresolved));
        DeliveryClient mixedClient = new DeliveryClient(
                base.clients().getFirst().id(),
                base.clients().getFirst().name(),
                List.of(mixedGroup));
        return withClients(base, List.of(mixedClient));
    }

    private DeliveryPlan planWithOnlyUnresolvedDestination() {
        DeliveryPlan base = plan();
        DeliveryGroup originalGroup = base.clients().getFirst().deliveryGroups().getFirst();
        DeliveryGroup unresolvedGroup = new DeliveryGroup(
                originalGroup.id(),
                originalGroup.name(),
                originalGroup.pickup(),
                List.of(unresolvedPackage()));
        DeliveryClient unresolvedClient = new DeliveryClient(
                base.clients().getFirst().id(),
                base.clients().getFirst().name(),
                List.of(unresolvedGroup));
        return withClients(base, List.of(unresolvedClient));
    }

    private DeliveryPackage unresolvedPackage() {
        return new DeliveryPackage(
                "package-unresolved",
                "Colis sans coordonnées",
                new BigDecimal("20"),
                null,
                new Location("Destination saisie", "Destination saisie", null, null),
                null);
    }

    private DeliveryPlan withClients(DeliveryPlan base, List<DeliveryClient> clients) {
        return new DeliveryPlan(
                base.schemaVersion(),
                base.pattern(),
                base.planId(),
                base.name(),
                base.departureAt(),
                base.timezone(),
                base.settings(),
                base.depot(),
                base.redistributionHub(),
                base.couriers(),
                base.packages(),
                clients);
    }

    private DeliveryPlan twoCourierPlan() {
        Location collectorOrigin = location("Origine collecteur", -18.90, 47.50);
        Location distributorOrigin = location("Origine distributeur", -18.89, 47.49);
        Location pickup = location("Retrait", -18.91, 47.51);
        Location hub = location("Siège", -18.92, 47.52);
        DeliveryPackage fastPackage = new DeliveryPackage(
                "package-fast",
                "Colis urgent",
                new BigDecimal("4"),
                null,
                location("Destination urgente", -18.93, 47.53),
                new DeliveryTimeWindow(START, START.plusMinutes(30)));
        DeliveryPackage localPackage = new DeliveryPackage(
                "package-local",
                "Colis local",
                new BigDecimal("4"),
                null,
                location("Destination proche", -18.925, 47.525),
                null);
        DeliveryGroup group = new DeliveryGroup(
                "group-transfer",
                "Retrait commun",
                pickup,
                List.of(fastPackage, localPackage));
        DeliveryClient client = new DeliveryClient("client-transfer", "Client", List.of(group));
        Courier collector = new Courier(
                "courier-collector",
                "Collecteur",
                VehicleType.MOTO,
                new BigDecimal("8"),
                new BigDecimal("3.6"),
                START,
                collectorOrigin);
        Courier distributor = new Courier(
                "courier-distributor",
                "Distributeur",
                VehicleType.MOTO,
                new BigDecimal("4"),
                new BigDecimal("36"),
                START,
                distributorOrigin);
        return new DeliveryPlan(
                "2.1",
                PlanningPattern.MULTI_CLIENT_MULTI_PICKUP_DELIVERY,
                "plan-hub-transfer",
                "Plan siège avec transfert",
                START,
                "Indian/Antananarivo",
                new PlanSettings(false, RoutingPreset.MAIN_ROADS),
                null,
                hub,
                List.of(collector, distributor),
                List.of(),
                List.of(client));
    }

    private DeliveryPlan feedbackPlan() {
        Location origin = location("Origine", -18.90, 47.50);
        Location urgentPickup = location("Retrait urgent", -18.91, 47.51);
        Location distantPickup = location("Retrait distant", -18.80, 47.60);
        Location hub = location("Siège", -18.92, 47.52);
        DeliveryTimeWindow urgentWindow = new DeliveryTimeWindow(START, START.plusMinutes(31));
        DeliveryPackage urgentOne = new DeliveryPackage(
                "package-urgent-1", "Urgent 1", BigDecimal.ONE, null,
                location("Destination urgente 1", -18.93, 47.53), urgentWindow);
        DeliveryPackage urgentTwo = new DeliveryPackage(
                "package-urgent-2", "Urgent 2", BigDecimal.ONE, null,
                location("Destination urgente 2", -18.94, 47.54), urgentWindow);
        DeliveryPackage distant = new DeliveryPackage(
                "package-distant", "Distant", BigDecimal.ONE, null,
                location("Destination distante", -18.95, 47.55), null);
        DeliveryGroup urgentGroup = new DeliveryGroup(
                "group-urgent", "Retrait urgent", urgentPickup, List.of(urgentOne, urgentTwo));
        DeliveryGroup distantGroup = new DeliveryGroup(
                "group-distant", "Retrait distant", distantPickup, List.of(distant));
        DeliveryClient client = new DeliveryClient(
                "client-feedback", "Client", List.of(urgentGroup, distantGroup));
        Courier courier = new Courier(
                "courier-feedback", "Soa", VehicleType.MOTO,
                new BigDecimal("10"), new BigDecimal("36"), START, origin);
        return new DeliveryPlan(
                "2.1",
                PlanningPattern.MULTI_CLIENT_MULTI_PICKUP_DELIVERY,
                "plan-hub-feedback",
                "Plan siège avec recalcul",
                START,
                "Indian/Antananarivo",
                new PlanSettings(false, RoutingPreset.MAIN_ROADS),
                null,
                hub,
                List.of(courier),
                List.of(),
                List.of(client));
    }

    @SafeVarargs
    private static RoutingMatrixResult matrix(List<Double>... rows) {
        return new RoutingMatrixResult(
                "TEST", RoutingPreset.MAIN_ROADS, List.of(), List.of(rows), List.of(rows), List.of());
    }

    private static List<Double> row(double... values) {
        return Arrays.stream(values).boxed().toList();
    }

    private Location location(String label, double latitude, double longitude) {
        return new Location(label, label, latitude, longitude);
    }
}
