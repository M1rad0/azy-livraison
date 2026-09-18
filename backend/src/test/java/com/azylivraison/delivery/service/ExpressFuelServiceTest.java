package com.azylivraison.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.administration.domain.ConfirmedLocation;
import com.azylivraison.administration.domain.CourierProfile;
import com.azylivraison.administration.service.CourierProfileService;
import com.azylivraison.administration.service.EffectiveCourierProfile;
import com.azylivraison.delivery.domain.ExpressFuelAdjustment;
import com.azylivraison.delivery.domain.ExpressFuelCalculation;
import com.azylivraison.delivery.domain.ExpressFuelCalculationStatus;
import com.azylivraison.delivery.domain.ExpressFuelLine;
import com.azylivraison.delivery.repository.ExpressFuelRepository;
import com.azylivraison.delivery.repository.ExpressFuelRepository.DeliveryStop;
import com.azylivraison.delivery.repository.ExpressFuelRepository.RequestSnapshot;
import com.azylivraison.optimization.service.ExpressRouteOptimizer;
import com.azylivraison.planning.domain.RoutingPreset;
import com.azylivraison.planning.domain.VehicleType;
import com.azylivraison.routing.domain.RoutingPoint;
import com.azylivraison.routing.service.RoutingMatrixResult;
import com.azylivraison.routing.service.RoutingRouteResult;
import com.azylivraison.routing.service.RoutingService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExpressFuelServiceTest {

    private static final UUID REQUEST_ID = UUID.fromString("aa000000-0000-0000-0000-000000000001");
    private static final UUID COURIER_ID = UUID.fromString("aa000000-0000-0000-0000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("aa000000-0000-0000-0000-000000000003");
    private static final UUID PREVIOUS_REQUEST_ID = UUID.fromString("aa000000-0000-0000-0000-000000000004");
    private static final UUID PREVIOUS_PACKAGE_ID = UUID.fromString("aa000000-0000-0000-0000-000000000005");
    private static final UUID SECOND_COURIER_ID = UUID.fromString("aa000000-0000-0000-0000-000000000006");
    private static final UUID SECOND_PACKAGE_ID = UUID.fromString("aa000000-0000-0000-0000-000000000007");
    private static final UUID THIRD_COURIER_ID = UUID.fromString("aa000000-0000-0000-0000-000000000008");
    private static final UUID SECOND_PREVIOUS_PACKAGE_ID = UUID.fromString("aa000000-0000-0000-0000-000000000009");
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 9, 7);

    @Mock ExpressFuelRepository repository;
    @Mock CourierProfileService couriers;
    @Mock RoutingService routing;
    @Mock ExpressRouteOptimizer optimizer;
    ExpressFuelService service;

    @BeforeEach
    void setUp() {
        service = new ExpressFuelService(repository, couriers, routing, optimizer);
    }

    @Test
    void calculatesAndSeparatelySnapshotsFuelForTheActualCourier() {
        RequestSnapshot request = request();
        ExpressFuelCalculation waiting = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.WAITING_FOR_OUTCOMES, 0, List.of());
        ExpressFuelCalculation completed = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.CALCULATED, 1, List.of());
        when(repository.requestSnapshot(REQUEST_ID)).thenReturn(Optional.of(request));
        when(repository.requestsForChainDate(SERVICE_DATE)).thenReturn(List.of(request));
        when(repository.find(REQUEST_ID)).thenReturn(Optional.of(waiting), Optional.of(completed));
        when(repository.deliveryStops(REQUEST_ID)).thenReturn(List.of(
                new DeliveryStop(PACKAGE_ID, "Destination", -18.9, 47.5, COURIER_ID)));
        when(couriers.findById(COURIER_ID)).thenReturn(courier(true, new BigDecimal("40"), 5_000));
        when(routing.matrix(anyList())).thenReturn(matrix(10_000));
        when(optimizer.optimize(anyList(), eq(true)))
                .thenReturn(new ExpressRouteOptimizer.Result(List.of(0), 10_000));
        when(repository.replace(eq(REQUEST_ID), eq(0L), eq(ExpressFuelCalculationStatus.CALCULATED),
                eq(true), anyList(), any(Instant.class))).thenReturn(true);

        service.recalculate(REQUEST_ID, 0L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExpressFuelLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(repository).replace(eq(REQUEST_ID), eq(0L), eq(ExpressFuelCalculationStatus.CALCULATED),
                eq(true), lines.capture(), any(Instant.class));
        assertThat(lines.getValue()).singleElement().satisfies(line -> {
            assertThat(line.courierId()).isEqualTo(COURIER_ID);
            assertThat(line.packageIds()).containsExactly(PACKAGE_ID);
            assertThat(line.repositionDistanceMeters()).isNull();
            assertThat(line.automaticDistanceMeters()).isEqualTo(10_000);
            assertThat(line.suggestedFuelAriary()).isEqualTo(1_250);
            assertThat(line.finalFuelAriary()).isEqualTo(1_250);
            assertThat(line.manual()).isFalse();
        });
    }

    @Test
    void addsTheRoadRepositioningFromThePreviousExpressToTheNextPickup() {
        RequestSnapshot previousRequest = new RequestSnapshot(
                PREVIOUS_REQUEST_ID, "Premier client", "Premier retrait",
                -18.90, 47.50, false, SERVICE_DATE, true, Instant.parse("2026-09-07T06:00:00Z"));
        RequestSnapshot currentRequest = request();
        ExpressFuelLine previousLine = new ExpressFuelLine(
                COURIER_ID, "Livreur", "0320000000", List.of(PREVIOUS_PACKAGE_ID),
                null, 8_000L, 8_000, true, new BigDecimal("40"), 5_000,
                1_000, 1_000, null, false);
        ExpressFuelCalculation previousCalculation = calculation(
                PREVIOUS_REQUEST_ID, false, ExpressFuelCalculationStatus.CALCULATED, 1,
                List.of(previousLine));
        ExpressFuelCalculation waiting = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.WAITING_FOR_OUTCOMES, 0, List.of());
        ExpressFuelCalculation completed = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.CALCULATED, 1, List.of());

        when(repository.requestSnapshot(REQUEST_ID)).thenReturn(Optional.of(currentRequest));
        when(repository.requestsForChainDate(SERVICE_DATE)).thenReturn(List.of(previousRequest, currentRequest));
        when(repository.find(PREVIOUS_REQUEST_ID)).thenReturn(Optional.of(previousCalculation));
        when(repository.find(REQUEST_ID)).thenReturn(Optional.of(waiting), Optional.of(completed));
        when(repository.deliveryStops(PREVIOUS_REQUEST_ID)).thenReturn(List.of(
                new DeliveryStop(PREVIOUS_PACKAGE_ID, "Ancienne destination", -18.92, 47.51, COURIER_ID)));
        when(repository.deliveryStops(REQUEST_ID)).thenReturn(List.of(
                new DeliveryStop(PACKAGE_ID, "Destination", -18.9, 47.5, COURIER_ID)));
        when(repository.packageLocation(PREVIOUS_PACKAGE_ID)).thenReturn(Optional.of(
                new ExpressFuelRepository.LocationSnapshot(-18.92, 47.51)));
        when(couriers.findById(COURIER_ID)).thenReturn(courier(true, new BigDecimal("40"), 5_000));
        when(routing.matrix(anyList())).thenReturn(matrix(10_000));
        when(optimizer.optimize(anyList(), eq(true)))
                .thenReturn(new ExpressRouteOptimizer.Result(List.of(0), 10_000));
        when(routing.route(anyList())).thenReturn(new RoutingRouteResult(
                "OSRM", RoutingPreset.MAIN_ROADS, 5_000, 600, null, List.of(), List.of()));
        when(repository.replace(eq(REQUEST_ID), eq(0L), eq(ExpressFuelCalculationStatus.CALCULATED),
                eq(true), anyList(), any(Instant.class))).thenReturn(true);

        service.recalculate(REQUEST_ID, 0L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExpressFuelLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(repository).replace(eq(REQUEST_ID), eq(0L), eq(ExpressFuelCalculationStatus.CALCULATED),
                eq(true), lines.capture(), any(Instant.class));
        assertThat(lines.getValue()).singleElement().satisfies(line -> {
            assertThat(line.repositionDistanceMeters()).isEqualTo(5_000);
            assertThat(line.automaticDistanceMeters()).isEqualTo(15_000);
            assertThat(line.finalDistanceMeters()).isEqualTo(15_000);
            assertThat(line.suggestedFuelAriary()).isEqualTo(1_875);
        });
    }

    @Test
    void chainsOnlyTheCourierIntersectionAndKeepsSeparateOpenRoadRoutes() {
        RequestSnapshot previousRequest = new RequestSnapshot(
                PREVIOUS_REQUEST_ID, "Premier client", "Premier retrait",
                -18.90, 47.50, false, SERVICE_DATE, true,
                Instant.parse("2026-09-07T06:00:00Z"));
        RequestSnapshot currentRequest = new RequestSnapshot(
                REQUEST_ID, "Client", "Retrait", -18.80, 47.60, false,
                SERVICE_DATE, true, Instant.parse("2026-09-07T07:00:00Z"));
        ExpressFuelCalculation previousCalculation = calculation(
                PREVIOUS_REQUEST_ID, false, ExpressFuelCalculationStatus.CALCULATED, 1,
                List.of(
                        automaticLine(COURIER_ID, "Livreur 1", PREVIOUS_PACKAGE_ID, 8_000),
                        automaticLine(SECOND_COURIER_ID, "Livreur 2", SECOND_PREVIOUS_PACKAGE_ID, 9_000)));
        ExpressFuelCalculation waiting = calculation(
                REQUEST_ID, false, ExpressFuelCalculationStatus.WAITING_FOR_OUTCOMES, 0, List.of());
        ExpressFuelCalculation completed = calculation(
                REQUEST_ID, false, ExpressFuelCalculationStatus.CALCULATED, 1, List.of());

        when(repository.requestSnapshot(REQUEST_ID)).thenReturn(Optional.of(currentRequest));
        when(repository.requestsForChainDate(SERVICE_DATE))
                .thenReturn(List.of(previousRequest, currentRequest));
        when(repository.find(PREVIOUS_REQUEST_ID)).thenReturn(Optional.of(previousCalculation));
        when(repository.find(REQUEST_ID)).thenReturn(Optional.of(waiting), Optional.of(completed));
        when(repository.deliveryStops(PREVIOUS_REQUEST_ID)).thenReturn(List.of(
                new DeliveryStop(PREVIOUS_PACKAGE_ID, "Ancienne destination 1", -18.92, 47.51, COURIER_ID),
                new DeliveryStop(SECOND_PREVIOUS_PACKAGE_ID, "Ancienne destination 2",
                        -18.93, 47.52, SECOND_COURIER_ID)));
        when(repository.deliveryStops(REQUEST_ID)).thenReturn(List.of(
                new DeliveryStop(PACKAGE_ID, "Destination 2", -18.81, 47.61, SECOND_COURIER_ID),
                new DeliveryStop(SECOND_PACKAGE_ID, "Destination 3", -18.82, 47.62, THIRD_COURIER_ID)));
        when(repository.packageLocation(PREVIOUS_PACKAGE_ID)).thenReturn(Optional.of(
                new ExpressFuelRepository.LocationSnapshot(-18.92, 47.51)));
        when(repository.packageLocation(SECOND_PREVIOUS_PACKAGE_ID)).thenReturn(Optional.of(
                new ExpressFuelRepository.LocationSnapshot(-18.93, 47.52)));
        when(repository.packageLocation(PACKAGE_ID)).thenReturn(Optional.of(
                new ExpressFuelRepository.LocationSnapshot(-18.81, 47.61)));
        when(repository.packageLocation(SECOND_PACKAGE_ID)).thenReturn(Optional.of(
                new ExpressFuelRepository.LocationSnapshot(-18.82, 47.62)));
        when(couriers.findById(SECOND_COURIER_ID))
                .thenReturn(courier(SECOND_COURIER_ID, "Livreur 2", true, new BigDecimal("40"), 5_000));
        when(couriers.findById(THIRD_COURIER_ID))
                .thenReturn(courier(THIRD_COURIER_ID, "Livreur 3", true, new BigDecimal("40"), 5_000));
        when(routing.matrix(anyList())).thenReturn(matrix(10_000));
        when(optimizer.optimize(anyList(), eq(false)))
                .thenReturn(new ExpressRouteOptimizer.Result(List.of(0), 10_000));
        when(routing.route(anyList())).thenReturn(new RoutingRouteResult(
                "OSRM", RoutingPreset.MAIN_ROADS, 5_000, 600, null, List.of(), List.of()));
        when(repository.replace(eq(REQUEST_ID), eq(0L), eq(ExpressFuelCalculationStatus.CALCULATED),
                eq(false), anyList(), any(Instant.class))).thenReturn(true);

        service.recalculate(REQUEST_ID, 0L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExpressFuelLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(repository).replace(eq(REQUEST_ID), eq(0L), eq(ExpressFuelCalculationStatus.CALCULATED),
                eq(false), lines.capture(), any(Instant.class));
        assertThat(lines.getValue()).extracting(ExpressFuelLine::courierId)
                .containsExactly(SECOND_COURIER_ID, THIRD_COURIER_ID);
        assertThat(lines.getValue().get(0)).satisfies(line -> {
            assertThat(line.repositionDistanceMeters()).isEqualTo(5_000);
            assertThat(line.automaticDistanceMeters()).isEqualTo(15_000);
        });
        assertThat(lines.getValue().get(1)).satisfies(line -> {
            assertThat(line.repositionDistanceMeters()).isNull();
            assertThat(line.automaticDistanceMeters()).isEqualTo(10_000);
        });
        verify(routing, times(2)).matrix(anyList());
        verify(optimizer, times(2)).optimize(anyList(), eq(false));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoutingPoint>> repositionPoints = ArgumentCaptor.forClass(List.class);
        verify(routing).route(repositionPoints.capture());
        assertThat(repositionPoints.getValue()).extracting(RoutingPoint::id)
                .containsExactly("previous-end", "next-pickup");
        assertThat(repositionPoints.getValue().get(0).latitude()).isEqualTo(-18.93);
        assertThat(repositionPoints.getValue().get(1).latitude()).isEqualTo(-18.80);
    }

    @Test
    void doesNotIgnoreAnEarlierExpressWhoseCourierPositionIsStillUnknown() {
        RequestSnapshot previousPending = new RequestSnapshot(
                PREVIOUS_REQUEST_ID, "Premier client", "Premier retrait",
                -18.90, 47.50, true, SERVICE_DATE, false,
                Instant.parse("2026-09-07T06:00:00Z"));
        RequestSnapshot currentRequest = request();
        ExpressFuelCalculation waiting = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.WAITING_FOR_OUTCOMES, 0, List.of());
        ExpressFuelCalculation failed = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.ROUTING_FAILED, 1, List.of());

        when(repository.requestSnapshot(REQUEST_ID)).thenReturn(Optional.of(currentRequest));
        when(repository.requestsForChainDate(SERVICE_DATE))
                .thenReturn(List.of(previousPending, currentRequest));
        when(repository.deliveryStops(PREVIOUS_REQUEST_ID)).thenReturn(List.of());
        when(repository.involvedCourierIds(PREVIOUS_REQUEST_ID)).thenReturn(List.of(COURIER_ID));
        when(repository.deliveryStops(REQUEST_ID)).thenReturn(List.of(
                new DeliveryStop(PACKAGE_ID, "Destination", -18.9, 47.5, COURIER_ID)));
        when(repository.find(REQUEST_ID)).thenReturn(Optional.of(waiting), Optional.of(failed));
        when(couriers.findById(COURIER_ID)).thenReturn(courier(true, new BigDecimal("40"), 5_000));
        when(routing.matrix(anyList())).thenReturn(matrix(10_000));
        when(optimizer.optimize(anyList(), eq(true)))
                .thenReturn(new ExpressRouteOptimizer.Result(List.of(0), 10_000));
        when(repository.markFailure(eq(REQUEST_ID), eq(0L), eq(true),
                eq(ExpressFuelCalculationStatus.ROUTING_FAILED), any(), any(Instant.class)))
                .thenReturn(true);

        ExpressFuelCalculation result = service.recalculate(REQUEST_ID, 0L);

        assertThat(result.status()).isEqualTo(ExpressFuelCalculationStatus.ROUTING_FAILED);
        verify(repository, never()).replace(eq(REQUEST_ID), eq(0L),
                eq(ExpressFuelCalculationStatus.CALCULATED), eq(true), anyList(), any(Instant.class));
        verify(repository).markFailure(eq(REQUEST_ID), eq(0L), eq(true),
                eq(ExpressFuelCalculationStatus.ROUTING_FAILED),
                org.mockito.ArgumentMatchers.contains("demande express"), any(Instant.class));
    }

    @Test
    void refusesToRecalculateAfterFinancialFinalization() {
        when(repository.requestSnapshot(REQUEST_ID)).thenReturn(Optional.of(request()));
        when(repository.isFinanciallyFinalized(REQUEST_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.recalculate(REQUEST_ID, 0L))
                .isInstanceOf(InvalidDeliveryRequestFilterException.class)
                .hasMessageContaining("figé");
    }

    @Test
    void persistsRoutingFailureInsteadOfThrowingIt() {
        RequestSnapshot request = request();
        ExpressFuelCalculation waiting = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.WAITING_FOR_OUTCOMES, 0, List.of());
        ExpressFuelCalculation failed = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.ROUTING_FAILED, 1, List.of());
        when(repository.requestSnapshot(REQUEST_ID)).thenReturn(Optional.of(request));
        when(repository.requestsForChainDate(SERVICE_DATE)).thenReturn(List.of(request));
        when(repository.find(REQUEST_ID)).thenReturn(Optional.of(waiting), Optional.of(failed));
        when(repository.deliveryStops(REQUEST_ID)).thenReturn(List.of(
                new DeliveryStop(PACKAGE_ID, "Destination", -18.9, 47.5, COURIER_ID)));
        when(couriers.findById(COURIER_ID)).thenReturn(courier(true, new BigDecimal("40"), 5_000));
        when(routing.matrix(anyList())).thenThrow(new RuntimeException("OSRM indisponible"));
        when(repository.markFailure(eq(REQUEST_ID), eq(0L), eq(true),
                eq(ExpressFuelCalculationStatus.ROUTING_FAILED), any(), any(Instant.class))).thenReturn(true);

        ExpressFuelCalculation result = service.recalculate(REQUEST_ID, 0L);

        assertThat(result.status()).isEqualTo(ExpressFuelCalculationStatus.ROUTING_FAILED);
        verify(repository).markFailure(eq(REQUEST_ID), eq(0L), eq(true),
                eq(ExpressFuelCalculationStatus.ROUTING_FAILED),
                org.mockito.ArgumentMatchers.contains("OSRM indisponible"), any(Instant.class));
    }

    @Test
    void keepsManualInputIncompleteUntilEveryActualCourierHasALine() {
        RequestSnapshot request = request();
        ExpressFuelCalculation failed = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.ROUTING_FAILED, 1, List.of());
        ExpressFuelLine firstLine = manualLine(
                COURIER_ID, "Livreur 1", PACKAGE_ID, 10_000, 1_250);
        ExpressFuelLine secondLine = manualLine(
                SECOND_COURIER_ID, "Livreur 2", SECOND_PACKAGE_ID, 8_000, 1_000);
        ExpressFuelCalculation partial = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.MANUAL_INCOMPLETE, 2,
                List.of(firstLine));
        ExpressFuelCalculation complete = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.MANUAL, 3,
                List.of(firstLine, secondLine));

        when(repository.requestSnapshot(REQUEST_ID)).thenReturn(Optional.of(request));
        when(repository.isCompletedWithFinalOutcomes(REQUEST_ID)).thenReturn(true);
        when(repository.requestsForChainDate(SERVICE_DATE)).thenReturn(List.of(request));
        when(repository.deliveryStops(REQUEST_ID)).thenReturn(List.of(
                new DeliveryStop(PACKAGE_ID, "Destination 1", -18.9, 47.5, COURIER_ID),
                new DeliveryStop(SECOND_PACKAGE_ID, "Destination 2", -18.8, 47.6, SECOND_COURIER_ID)));
        when(repository.find(REQUEST_ID)).thenReturn(
                Optional.of(failed), Optional.of(partial), Optional.of(partial),
                Optional.of(partial), Optional.of(complete), Optional.of(complete));
        when(repository.replace(eq(REQUEST_ID), anyLong(), any(), eq(true), anyList(), any(Instant.class)))
                .thenReturn(true);
        when(couriers.findById(COURIER_ID))
                .thenReturn(courier(COURIER_ID, "Livreur 1", true, new BigDecimal("40"), 5_000));
        when(couriers.findById(SECOND_COURIER_ID))
                .thenReturn(courier(SECOND_COURIER_ID, "Livreur 2", true, new BigDecimal("40"), 5_000));

        ExpressFuelCalculation afterFirst = service.adjust(REQUEST_ID, 1, List.of(
                new ExpressFuelAdjustment(COURIER_ID, 10_000L, null, "Trajet vérifié")));
        ExpressFuelCalculation afterSecond = service.adjust(REQUEST_ID, 2, List.of(
                new ExpressFuelAdjustment(SECOND_COURIER_ID, 8_000L, null, "Trajet vérifié")));

        assertThat(afterFirst.status()).isEqualTo(ExpressFuelCalculationStatus.MANUAL_INCOMPLETE);
        assertThat(afterSecond.status()).isEqualTo(ExpressFuelCalculationStatus.MANUAL);
        ArgumentCaptor<ExpressFuelCalculationStatus> statuses =
                ArgumentCaptor.forClass(ExpressFuelCalculationStatus.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExpressFuelLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).replace(eq(REQUEST_ID), anyLong(), statuses.capture(),
                eq(true), lines.capture(), any(Instant.class));
        assertThat(statuses.getAllValues()).containsExactly(
                ExpressFuelCalculationStatus.MANUAL_INCOMPLETE,
                ExpressFuelCalculationStatus.MANUAL);
        assertThat(lines.getAllValues().get(0)).extracting(ExpressFuelLine::courierId)
                .containsExactly(COURIER_ID);
        assertThat(lines.getAllValues().get(0).getFirst().packageIds())
                .containsExactly(PACKAGE_ID);
        assertThat(lines.getAllValues().get(1)).extracting(ExpressFuelLine::courierId)
                .containsExactly(COURIER_ID, SECOND_COURIER_ID);
        assertThat(lines.getAllValues().get(1).get(1).packageIds())
                .containsExactly(SECOND_PACKAGE_ID);
    }

    @Test
    void downgradesAManualCalculationWhenTheActualCourierAssignmentChanges() {
        RequestSnapshot request = request();
        ExpressFuelLine staleLine = manualLine(
                COURIER_ID, "Livreur 1", PACKAGE_ID, 10_000, 1_250);
        ExpressFuelCalculation manual = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.MANUAL, 4, List.of(staleLine));
        ExpressFuelCalculation incomplete = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.MANUAL_INCOMPLETE, 5,
                List.of(staleLine));

        when(repository.requestSnapshot(REQUEST_ID)).thenReturn(Optional.of(request));
        when(repository.requestsForChainDate(SERVICE_DATE)).thenReturn(List.of(request));
        when(repository.deliveryStops(REQUEST_ID)).thenReturn(List.of(
                new DeliveryStop(PACKAGE_ID, "Destination", -18.9, 47.5, SECOND_COURIER_ID)));
        when(repository.find(REQUEST_ID)).thenReturn(Optional.of(manual), Optional.of(incomplete));
        when(repository.replace(eq(REQUEST_ID), eq(4L),
                eq(ExpressFuelCalculationStatus.MANUAL_INCOMPLETE), eq(true),
                anyList(), any(Instant.class))).thenReturn(true);

        ExpressFuelCalculation result = service.recalculate(REQUEST_ID, null);

        assertThat(result.status()).isEqualTo(ExpressFuelCalculationStatus.MANUAL_INCOMPLETE);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExpressFuelLine>> reconciled = ArgumentCaptor.forClass(List.class);
        verify(repository).replace(eq(REQUEST_ID), eq(4L),
                eq(ExpressFuelCalculationStatus.MANUAL_INCOMPLETE), eq(true),
                reconciled.capture(), any(Instant.class));
        assertThat(reconciled.getValue()).singleElement().satisfies(line -> {
            assertThat(line.courierId()).isEqualTo(COURIER_ID);
            assertThat(line.packageIds()).isEmpty();
            assertThat(line.automaticDistanceMeters()).isNull();
            assertThat(line.suggestedFuelAriary()).isZero();
            assertThat(line.finalFuelAriary()).isEqualTo(1_250);
        });
    }

    @Test
    void acceptsAnExplicitManualAmountWhenFuelEfficiencyIsNotConfigured() {
        RequestSnapshot request = request();
        ExpressFuelCalculation failed = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.CONFIGURATION_MISSING, 1, List.of());
        ExpressFuelLine savedLine = new ExpressFuelLine(
                COURIER_ID, "Livreur", "0320000000", List.of(PACKAGE_ID),
                null, null, 0, true, null, 5_000, 0, 2_000,
                "Montant constaté", true);
        ExpressFuelCalculation saved = calculation(
                REQUEST_ID, true, ExpressFuelCalculationStatus.MANUAL, 2, List.of(savedLine));

        when(repository.requestSnapshot(REQUEST_ID)).thenReturn(Optional.of(request));
        when(repository.isCompletedWithFinalOutcomes(REQUEST_ID)).thenReturn(true);
        when(repository.requestsForChainDate(SERVICE_DATE)).thenReturn(List.of(request));
        when(repository.deliveryStops(REQUEST_ID)).thenReturn(List.of(
                new DeliveryStop(PACKAGE_ID, "Destination", -18.9, 47.5, COURIER_ID)));
        when(repository.find(REQUEST_ID)).thenReturn(
                Optional.of(failed), Optional.of(saved), Optional.of(saved));
        when(couriers.findById(COURIER_ID)).thenReturn(courier(true, null, 5_000));
        when(repository.replace(eq(REQUEST_ID), eq(1L), eq(ExpressFuelCalculationStatus.MANUAL),
                eq(true), anyList(), any(Instant.class))).thenReturn(true);

        ExpressFuelCalculation result = service.adjust(REQUEST_ID, 1, List.of(
                new ExpressFuelAdjustment(COURIER_ID, null, 2_000L, "Montant constaté")));

        assertThat(result.status()).isEqualTo(ExpressFuelCalculationStatus.MANUAL);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExpressFuelLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(repository).replace(eq(REQUEST_ID), eq(1L), eq(ExpressFuelCalculationStatus.MANUAL),
                eq(true), lines.capture(), any(Instant.class));
        assertThat(lines.getValue()).singleElement().satisfies(line -> {
            assertThat(line.packageIds()).containsExactly(PACKAGE_ID);
            assertThat(line.fuelEfficiencyKmPerLiter()).isNull();
            assertThat(line.finalFuelAriary()).isEqualTo(2_000);
        });
    }

    private RequestSnapshot request() {
        return new RequestSnapshot(
                REQUEST_ID, "Client", "Retrait", -18.91, 47.52, true,
                SERVICE_DATE, true, Instant.parse("2026-09-07T07:00:00Z"));
    }

    private ExpressFuelCalculation calculation(
            UUID requestId, boolean returnToPickup, ExpressFuelCalculationStatus status,
            long version, List<ExpressFuelLine> lines) {
        return new ExpressFuelCalculation(requestId, "Client", "Retrait", status, returnToPickup,
                status == ExpressFuelCalculationStatus.ROUTING_FAILED ? "échec" : null,
                0, 0, 0, version, null, lines);
    }

    private RoutingMatrixResult matrix(double distance) {
        return new RoutingMatrixResult(
                "OSRM", RoutingPreset.MAIN_ROADS, List.of(),
                List.of(List.of(0d, 1d), List.of(1d, 0d)),
                List.of(List.of(0d, distance), List.of(distance, 0d)), List.of());
    }

    private EffectiveCourierProfile courier(boolean enabled, BigDecimal efficiency, long price) {
        return courier(COURIER_ID, "Livreur", enabled, efficiency, price);
    }

    private EffectiveCourierProfile courier(UUID courierId, String name,
            boolean enabled, BigDecimal efficiency, long price) {
        CourierProfile profile = new CourierProfile(
                courierId, null, name, "0320000000", "0320000000", null,
                true, VehicleType.MOTO, null, null, null, null,
                null, null, null, LocalTime.of(6, 0),
                new ConfirmedLocation("Départ", null, -18.9, 47.5),
                0, Instant.EPOCH, Instant.EPOCH);
        return new EffectiveCourierProfile(profile, new BigDecimal("30"), new BigDecimal("25"),
                1, 3, enabled, efficiency, price);
    }

    private ExpressFuelLine manualLine(
            UUID courierId, String name, UUID packageId, long distance, long fuel) {
        return new ExpressFuelLine(
                courierId, name, "0320000000", List.of(packageId), null, null, distance,
                true, new BigDecimal("40"), 5_000, 0, fuel, "Trajet vérifié", true);
    }

    private ExpressFuelLine automaticLine(
            UUID courierId, String name, UUID packageId, long distance) {
        return new ExpressFuelLine(
                courierId, name, "0320000000", List.of(packageId), null, distance, distance,
                true, new BigDecimal("40"), 5_000, 1_000, 1_000, null, false);
    }
}
