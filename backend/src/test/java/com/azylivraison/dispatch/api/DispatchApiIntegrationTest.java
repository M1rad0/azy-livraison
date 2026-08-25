package com.azylivraison.dispatch.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;

import com.azylivraison.optimization.domain.OptimizationComparisonResult.CourierHubManifest;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.HubExecution;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.HubRole;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OperationalPolicySnapshot;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.PackageReference;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.PackageTransfer;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.StrategyMetrics;
import com.azylivraison.optimization.domain.OptimizationResult;
import com.azylivraison.optimization.domain.OptimizationResult.CourierRoute;
import com.azylivraison.optimization.domain.OptimizationResult.DataContext;
import com.azylivraison.optimization.domain.OptimizationResult.OptimizationStatus;
import com.azylivraison.optimization.domain.OptimizationResult.OptimizationSummary;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStop;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStopType;
import com.azylivraison.optimization.domain.ViaHubOptimizationResult;
import com.azylivraison.optimization.domain.ViaHubRunPolicy;
import com.azylivraison.optimization.service.ViaHubOptimizationFacade;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryGroup;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.DeliveryPlan;
import com.azylivraison.planning.domain.RoutingPreset;
import com.azylivraison.routing.domain.GeoJsonLineString;
import com.azylivraison.routing.domain.RoutingLeg;
import com.azylivraison.routing.domain.RoutingWaypoint;
import com.azylivraison.routing.provider.RoutingProvider;
import com.azylivraison.routing.provider.RoutingRouteData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DispatchApiIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CLIENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID COURIER_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID REQUEST_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID PACKAGE_ID = UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(
            2026, 8, 24, 6, 0, 0, 0, ZoneOffset.ofHours(3));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ViaHubOptimizationFacade optimizationFacade;

    @MockitoBean
    private RoutingProvider routingProvider;

    @BeforeEach
    void prepareQueue() {
        reset(optimizationFacade, routingProvider);
        when(routingProvider.name()).thenReturn("TEST");
        insertAccount(ADMIN_ID, "dispatch-admin", "Administrateur", "ADMIN");
        insertAccount(CLIENT_ID, "dispatch-client", "Boutique Soa", "CLIENT");
        jdbcTemplate.update("""
                UPDATE operational_settings
                SET default_hub_label = 'Point de ralliement Antanimena',
                    default_hub_query = 'Antanimena, Antananarivo',
                    default_hub_latitude = -18.8952,
                    default_hub_longitude = 47.5183
                WHERE id = 1
                """);
        jdbcTemplate.update("""
                INSERT INTO courier_profile (
                    id, name, contact, vehicle_type, default_departure_time,
                    departure_location_label, departure_location_query,
                    departure_latitude, departure_longitude
                ) VALUES (?, 'Soa', '034 00 000 01', 'MOTO', '06:00:00',
                          'Alasora', 'Alasora, Madagascar', -18.9553, 47.5675)
                """, COURIER_ID);
        jdbcTemplate.update("""
                INSERT INTO delivery_request (
                    id, owner_account_id, requested_date,
                    pickup_label, pickup_query, pickup_latitude, pickup_longitude,
                    capacity_limit_snapshot, capacity_current_snapshot,
                    capacity_requested_snapshot, capacity_projected_snapshot
                ) VALUES (?, ?, '2026-08-24',
                          'Boutique Analakely', 'Analakely, Antananarivo', -18.9081, 47.5210,
                          100, 0, 1, 1)
                """, REQUEST_ID, CLIENT_ID);
        jdbcTemplate.update("""
                INSERT INTO delivery_package (
                    id, delivery_request_id, sequence_number,
                    recipient_name, faritra,
                    destination_label, destination_query,
                    destination_latitude, destination_longitude,
                    contact, amount_to_collect_ariary, mobile_money_paid,
                    pricing_zone_id, pricing_zone_name, delivery_fee_ariary,
                    distance_from_center_meters, pricing_zone_max_distance_meters,
                    pricing_policy_version,
                    effective_weight_kg, weight_source, priority_at
                ) VALUES (?, ?, 1, 'Rakoto', TRUE,
                          'Ampasapito', 'Ampasapito, Antananarivo', -18.8990, 47.5440,
                          '034 11 222 33', 45000, FALSE,
                          '71000000-0000-0000-0000-000000000001', 'Centre-ville', 3000,
                          2448, 2500, 0,
                          2.5, 'PROVIDED', CURRENT_TIMESTAMP)
                """, PACKAGE_ID, REQUEST_ID);
        when(optimizationFacade.optimize(any(DeliveryPlan.class), any(ViaHubRunPolicy.class)))
                .thenAnswer(invocation -> successfulResult(invocation.getArgument(0), invocation.getArgument(1)));
    }

    @Test
    void persistsAndReturnsOneViaHubScheduleIdempotently() throws Exception {
        String body = """
                {
                  "serviceDate": "2026-08-24",
                  "courierIds": ["%s"]
                }
                """.formatted(COURIER_ID);

        MvcResult first = mockMvc.perform(post("/api/admin/dispatch-plans")
                        .with(admin())
                        .header("Idempotency-Key", "dispatch-2026-08-24")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.plan.strategy").value("VIA_HUB"))
                .andExpect(jsonPath("$.plan.status").value("READY"))
                .andExpect(jsonPath("$.plan.assignedPackageCount").value(1))
                .andExpect(jsonPath("$.result.summary.assignedPackageCount").value(1))
                .andExpect(jsonPath("$.hubExecution.manifests[0].courierName").value("Soa"))
                .andExpect(jsonPath("$.packages[0].recipientName").value("Rakoto"))
                .andExpect(jsonPath("$.packages[0].cashToCollectAriary").value(45000))
                .andExpect(jsonPath("$.packages[0].pricingZoneName").value("Centre-ville"))
                .andExpect(jsonPath("$.packages[0].deliveryFeeAriary").value(3000))
                .andExpect(jsonPath("$.packages[0].distanceFromCenterMeters").value(2448))
                .andReturn();

        String id = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("plan").get("id").asText();

        mockMvc.perform(post("/api/admin/dispatch-plans")
                        .with(admin())
                        .header("Idempotency-Key", "dispatch-2026-08-24")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.id").value(id));

        mockMvc.perform(get("/api/admin/dispatch-plans/{id}", id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.id").value(id));

        mockMvc.perform(get("/api/admin/dispatch-plans")
                        .param("date", "2026-08-24")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].strategy").value("VIA_HUB"));

        verify(optimizationFacade, times(1)).optimize(any(DeliveryPlan.class), any(ViaHubRunPolicy.class));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_package WHERE id = ?", String.class, PACKAGE_ID))
                .isEqualTo("DISPATCHED");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_request WHERE id = ?", String.class, REQUEST_ID))
                .isEqualTo("DISPATCHED");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dispatch_stop WHERE dispatch_plan_id = ?", Integer.class, UUID.fromString(id)))
                .isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_fee_ariary FROM dispatch_package_outcome WHERE dispatch_plan_id = ?",
                Long.class, UUID.fromString(id))).isEqualTo(3000L);
    }

    @Test
    void keepsDispatchEndpointsRestrictedToAdministrators() throws Exception {
        mockMvc.perform(get("/api/admin/dispatch-plans").with(client()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void validatesTheCourierSelectionBeforeDispatching() throws Exception {
        mockMvc.perform(post("/api/admin/dispatch-plans")
                        .with(admin())
                        .header("Idempotency-Key", "invalid-dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceDate":"2026-08-24","courierIds":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISPATCH_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Validation impossible."));
    }

    @Test
    void recoversAStaleInterruptedDispatchBeforeCreatingTheDailyPlan() throws Exception {
        UUID stalePlanId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO dispatch_plan (
                    id, service_date, status, strategy, created_by_account_id,
                    idempotency_key, request_hash,
                    hub_label, hub_latitude, hub_longitude,
                    input_snapshot_json, policy_snapshot_json,
                    selected_package_count, selected_courier_count, created_at
                ) VALUES (?, '2026-08-24', 'GENERATING', 'VIA_HUB', ?,
                          ?, ?, 'Ancien siège', -18.90, 47.52, '{}', '{}', 1, 1, ?)
                """, stalePlanId, ADMIN_ID, "stale-" + stalePlanId, "0".repeat(64),
                Timestamp.from(Instant.now().minusSeconds(31 * 60)));
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day (
                    service_date, status, admissions_closed_at, dispatch_plan_id
                ) VALUES ('2026-08-24', 'DISPATCHING', CURRENT_TIMESTAMP, ?)
                """, stalePlanId);

        mockMvc.perform(post("/api/admin/dispatch-plans")
                        .with(admin())
                        .header("Idempotency-Key", "replacement-after-crash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceDate": "2026-08-24",
                                  "courierIds": ["%s"]
                                }
                                """.formatted(COURIER_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plan.status").value("READY"));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM dispatch_plan WHERE id = ?", String.class, stalePlanId))
                .isEqualTo("FAILED");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM delivery_operation_day_event
                WHERE service_date = '2026-08-24' AND event_type = 'STALE_DISPATCH_RECOVERED'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void turnsAStaleIdempotentReplayIntoAnAuditedFailure() throws Exception {
        UUID stalePlanId = UUID.randomUUID();
        String key = "stale-idempotent-replay";
        String canonical = "2026-08-24\n" + COURIER_ID + "\nALL_ELIGIBLE\nDEFAULT_HUB";
        String requestHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        jdbcTemplate.update("""
                INSERT INTO dispatch_plan (
                    id, service_date, status, strategy, created_by_account_id,
                    idempotency_key, request_hash,
                    hub_label, hub_latitude, hub_longitude,
                    input_snapshot_json, policy_snapshot_json,
                    selected_package_count, selected_courier_count, created_at
                ) VALUES (?, '2026-08-24', 'GENERATING', 'VIA_HUB', ?,
                          ?, ?, 'Ancien siège', -18.90, 47.52, '{}',
                          '{"pickupServiceSeconds":600,"hubTransferSeconds":900,"deliveryServiceSeconds":300,"deliveryWindowEarlyToleranceSeconds":0,"deliveryWindowLateToleranceSeconds":1800,"windowAppliesTo":"DELIVERY"}',
                          1, 1, ?)
                """, stalePlanId, ADMIN_ID, key, requestHash,
                Timestamp.from(Instant.now().minusSeconds(31 * 60)));
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day (
                    service_date, status, admissions_closed_at, dispatch_plan_id
                ) VALUES ('2026-08-24', 'DISPATCHING', CURRENT_TIMESTAMP, ?)
                """, stalePlanId);

        mockMvc.perform(post("/api/admin/dispatch-plans")
                        .with(admin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceDate": "2026-08-24",
                                  "courierIds": ["%s"]
                                }
                                """.formatted(COURIER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.id").value(stalePlanId.toString()))
                .andExpect(jsonPath("$.plan.status").value("FAILED"));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM delivery_operation_day WHERE service_date = '2026-08-24'
                """, String.class)).isEqualTo("OPEN");
        verify(optimizationFacade, times(0)).optimize(any(DeliveryPlan.class), any(ViaHubRunPolicy.class));
    }

    @Test
    void dispatchesOnlyTheSelectedActivityDate() throws Exception {
        UUID oldRequestId = UUID.randomUUID();
        UUID oldPackageId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO delivery_request (
                    id, owner_account_id, requested_date,
                    pickup_label, pickup_latitude, pickup_longitude,
                    capacity_limit_snapshot, capacity_current_snapshot,
                    capacity_requested_snapshot, capacity_projected_snapshot
                ) VALUES (?, ?, '2026-08-23', 'Ancien retrait', -18.91, 47.52, 100, 0, 1, 1)
                """, oldRequestId, CLIENT_ID);
        jdbcTemplate.update("""
                INSERT INTO delivery_package (
                    id, delivery_request_id, sequence_number,
                    recipient_name, faritra,
                    destination_label, destination_latitude, destination_longitude,
                    contact, amount_to_collect_ariary, mobile_money_paid,
                    effective_weight_kg, weight_source, priority_at
                ) VALUES (?, ?, 1, 'Ancien destinataire', FALSE,
                          'Ancienne destination', -18.90, 47.53,
                          '034 00 000 09', 1000, FALSE,
                          1, 'DEFAULT_SETTING', CURRENT_TIMESTAMP)
                """, oldPackageId, oldRequestId);

        mockMvc.perform(post("/api/admin/dispatch-plans")
                        .with(admin())
                        .header("Idempotency-Key", "strict-daily-queue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceDate": "2026-08-24",
                                  "courierIds": ["%s"]
                                }
                                """.formatted(COURIER_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plan.selectedPackageCount").value(1));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_package WHERE id = ?", String.class, oldPackageId))
                .isEqualTo("PENDING");
    }

    @Test
    void cancelsAnUntouchedPlanWithoutReopeningAdmissions() throws Exception {
        MvcResult created = createReadyPlan("cancel-ready-plan");
        String planId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("plan").get("id").asText();

        mockMvc.perform(post("/api/admin/dispatch-plans/{id}/cancel", planId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"reason":"Livreur indisponible"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("CANCELLED"))
                .andExpect(jsonPath("$.plan.version").value(1))
                .andExpect(jsonPath("$.plan.archiveReason").value("Livreur indisponible"))
                .andExpect(jsonPath("$.plan.editable").value(false));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_package WHERE id = ?", String.class, PACKAGE_ID))
                .isEqualTo("PENDING");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT dispatch_plan_id FROM delivery_package WHERE id = ?", UUID.class, PACKAGE_ID))
                .isNull();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM delivery_operation_day WHERE service_date = '2026-08-24'
                """, String.class)).isEqualTo("OPEN");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                SELECT admissions_closed_at IS NOT NULL
                FROM delivery_operation_day WHERE service_date = '2026-08-24'
                """, Boolean.class)).isTrue();
    }

    @Test
    void refusesToCancelAfterAResultWasRecorded() throws Exception {
        MvcResult created = createReadyPlan("cancel-after-outcome");
        String planId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("plan").get("id").asText();
        jdbcTemplate.update("""
                UPDATE delivery_package
                SET final_outcome = 'DELIVERED', actual_collected_ariary = 45000,
                    outcome_recorded_at = CURRENT_TIMESTAMP,
                    outcome_recorded_by_account_id = ?
                WHERE id = ?
                """, ADMIN_ID, PACKAGE_ID);

        mockMvc.perform(post("/api/admin/dispatch-plans/{id}/cancel", planId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"reason":"Changement tardif"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPATCH_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("résultat renseigné")));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT lifecycle_status FROM dispatch_plan WHERE id = ?", String.class, UUID.fromString(planId)))
                .isEqualTo("ACTIVE");
    }

    @Test
    void regeneratesTheExactCohortAndLinksBothRevisions() throws Exception {
        MvcResult created = createReadyPlan("regenerate-source");
        var source = objectMapper.readTree(created.getResponse().getContentAsString()).get("plan");
        String sourceId = source.get("id").asText();
        UUID laterRequestId = UUID.randomUUID();
        UUID laterPackageId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO delivery_request (
                    id, owner_account_id, requested_date,
                    pickup_label, pickup_latitude, pickup_longitude,
                    capacity_limit_snapshot, capacity_current_snapshot,
                    capacity_requested_snapshot, capacity_projected_snapshot
                ) VALUES (?, ?, '2026-08-24', 'Retrait arrivé plus tard', -18.91, 47.52, 100, 1, 1, 2)
                """, laterRequestId, CLIENT_ID);
        jdbcTemplate.update("""
                INSERT INTO delivery_package (
                    id, delivery_request_id, sequence_number,
                    recipient_name, faritra,
                    destination_label, destination_latitude, destination_longitude,
                    contact, amount_to_collect_ariary, mobile_money_paid,
                    effective_weight_kg, weight_source, priority_at
                ) VALUES (?, ?, 1, 'Nouvelle demande', FALSE,
                          'Nouvelle destination', -18.90, 47.53,
                          '034 00 000 10', 10000, FALSE,
                          1, 'DEFAULT_SETTING', CURRENT_TIMESTAMP)
                """, laterPackageId, laterRequestId);

        MvcResult regenerated = mockMvc.perform(post("/api/admin/dispatch-plans/{id}/regenerate", sourceId)
                        .with(admin())
                        .header("Idempotency-Key", "regenerate-exact-cohort")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "reason": "Changer la sélection de livreurs",
                                  "courierIds": ["%s"],
                                  "includeNewPending": false
                                }
                                """.formatted(COURIER_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plan.revision").value(2))
                .andExpect(jsonPath("$.plan.supersedesPlanId").value(sourceId))
                .andExpect(jsonPath("$.plan.selectedPackageCount").value(1))
                .andReturn();

        String replacementId = objectMapper.readTree(regenerated.getResponse().getContentAsString())
                .get("plan").get("id").asText();
        mockMvc.perform(get("/api/admin/dispatch-plans/{id}", sourceId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("SUPERSEDED"));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT dispatch_plan_id FROM delivery_package WHERE id = ?", UUID.class, PACKAGE_ID))
                .isEqualTo(UUID.fromString(replacementId));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_package WHERE id = ?", String.class, laterPackageId))
                .isEqualTo("PENDING");
        mockMvc.perform(post("/api/admin/dispatch-plans/{id}/regenerate", sourceId)
                        .with(admin())
                        .header("Idempotency-Key", "regenerate-exact-cohort")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 2,
                                  "reason": "Changer la sélection de livreurs",
                                  "courierIds": ["%s"],
                                  "includeNewPending": false
                                }
                                """.formatted(COURIER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.id").value(replacementId));
        verify(optimizationFacade, times(2)).optimize(any(DeliveryPlan.class), any(ViaHubRunPolicy.class));
    }

    @Test
    void replacesTheDistributionOrderWithAnOptimisticallyLockedManualLayout() throws Exception {
        MvcResult created = createReadyPlan("manual-layout-source");
        String planId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("plan").get("id").asText();
        when(routingProvider.route(any())).thenAnswer(invocation -> {
            List<com.azylivraison.routing.domain.RoutingPoint> points = invocation.getArgument(0);
            return new RoutingRouteData(
                    2_900,
                    480,
                    new GeoJsonLineString(List.of(
                            List.of(points.get(0).longitude(), points.get(0).latitude()),
                            List.of(points.get(1).longitude(), points.get(1).latitude()))),
                    List.of(
                            new RoutingWaypoint(points.get(0).id(), points.get(0).latitude(), points.get(0).longitude(),
                                    points.get(0).latitude(), points.get(0).longitude(), 0, "Hub"),
                            new RoutingWaypoint(points.get(1).id(), points.get(1).latitude(), points.get(1).longitude(),
                                    points.get(1).latitude(), points.get(1).longitude(), 0, "Destination")),
                    List.of(new RoutingLeg(
                            0, 1, points.get(0).id(), points.get(1).id(), 2_900, 480, "Route test")));
        });

        mockMvc.perform(put("/api/admin/dispatch-plans/{id}/manual-layout", planId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "reason": "Ordre confirmé par l'exploitation",
                                  "assignments": [{
                                    "packageId": "%s",
                                    "distributorCourierId": "%s",
                                    "deliveryOrder": 1
                                  }]
                                }
                                """.formatted(PACKAGE_ID, COURIER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.mode").value("MANUAL"))
                .andExpect(jsonPath("$.plan.version").value(1))
                .andExpect(jsonPath("$.packages[0].distributorCourierId").value(COURIER_ID.toString()))
                .andExpect(jsonPath("$.packages[0].deliveryOrder").value(1))
                .andExpect(jsonPath("$.stops[3].type").value("DELIVERY"));
    }

    private MvcResult createReadyPlan(String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/admin/dispatch-plans")
                        .with(admin())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceDate":"2026-08-24","courierIds":["%s"]}
                                """.formatted(COURIER_ID)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private ViaHubOptimizationResult successfulResult(DeliveryPlan plan, ViaHubRunPolicy policy) {
        Courier courier = plan.couriers().getFirst();
        DeliveryGroup group = plan.clients().getFirst().deliveryGroups().getFirst();
        DeliveryPackage deliveryPackage = group.packages().getFirst();
        OffsetDateTime pickupAt = DEPARTURE.plusMinutes(10);
        OffsetDateTime meetingAt = DEPARTURE.plusMinutes(20);
        OffsetDateTime redistributedAt = meetingAt.plus(policy.operationalPolicy().hubTransferDuration());
        OffsetDateTime deliveryAt = redistributedAt.plusMinutes(10);
        OffsetDateTime completedAt = deliveryAt.plus(policy.operationalPolicy().deliveryServiceDuration());

        RouteStop pickup = new RouteStop(
                1, RouteStopType.PICKUP,
                plan.clients().getFirst().id(), plan.clients().getFirst().name(),
                group.id(), group.name(), deliveryPackage.id(), deliveryPackage.label(),
                deliveryPackage.phone(), deliveryPackage.weightKg(), group.pickup(), pickupAt,
                1_000, 240, 1_000, 240);
        RouteStop delivery = new RouteStop(
                2, RouteStopType.DELIVERY,
                plan.clients().getFirst().id(), plan.clients().getFirst().name(),
                group.id(), group.name(), deliveryPackage.id(), deliveryPackage.label(),
                deliveryPackage.phone(), deliveryPackage.weightKg(), deliveryPackage.destination(), deliveryAt,
                4_000, 600, 5_000, 840);
        CourierRoute route = new CourierRoute(
                courier.id(), courier.name(), courier.vehicleType(), deliveryPackage.weightKg(),
                courier.maxWeightKg(), courier.maxWeightKg().subtract(deliveryPackage.weightKg()),
                new BigDecimal("8.33"), courier.departureLocation(), courier.departureAt(), completedAt,
                5_000, 3_000, List.of(pickup, delivery));
        OptimizationResult result = new OptimizationResult(
                "1.0", plan.planId(), DEPARTURE, OptimizationStatus.SUCCESS, "ROUTING_SUCCESS", 25,
                new DataContext("TEST", RoutingPreset.MAIN_ROADS, "OR_TOOLS", "9.15"),
                new OptimizationSummary(1, 1, 0, 1, 1, deliveryPackage.weightKg(), 5_000, 3_000),
                List.of(route), List.of(), List.of());

        PackageReference reference = new PackageReference(
                deliveryPackage.id(), deliveryPackage.label(), deliveryPackage.weightKg(),
                plan.clients().getFirst().id(), plan.clients().getFirst().name(), group.id(), group.name());
        CourierHubManifest manifest = new CourierHubManifest(
                courier.id(), courier.name(), List.of(HubRole.COLLECTION, HubRole.DISTRIBUTION),
                meetingAt, redistributedAt, 0,
                deliveryPackage.weightKg(), deliveryPackage.weightKg(),
                List.of(reference), List.of(reference), List.of(), List.of(), List.of(reference));
        HubExecution hubExecution = new HubExecution(
                plan.redistributionHub(), meetingAt, redistributedAt,
                2_000, 0, 3_000, 0,
                List.of(manifest),
                List.of(new PackageTransfer(
                        reference, courier.id(), courier.name(), courier.id(), courier.name(), false,
                        meetingAt, redistributedAt)));
        StrategyMetrics metrics = new StrategyMetrics(
                1, 1, 1, 0, 0, 0, 5_000, completedAt, 1,
                840, 0,
                policy.operationalPolicy().pickupServiceDuration().toSeconds()
                        + policy.operationalPolicy().hubTransferDuration().toSeconds()
                        + policy.operationalPolicy().deliveryServiceDuration().toSeconds());
        OperationalPolicySnapshot snapshot = new OperationalPolicySnapshot(
                policy.operationalPolicy().pickupServiceDuration().toSeconds(),
                policy.operationalPolicy().hubTransferDuration().toSeconds(),
                policy.operationalPolicy().deliveryServiceDuration().toSeconds(),
                policy.operationalPolicy().deliveryWindowEarlyTolerance().toSeconds(),
                policy.operationalPolicy().deliveryWindowLateTolerance().toSeconds(),
                "SERVICE_START");
        return new ViaHubOptimizationResult(result, hubExecution, metrics, snapshot);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt()
                .jwt(token -> token.subject("admin")
                        .claim("accountId", ADMIN_ID.toString())
                        .claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor client() {
        return jwt()
                .jwt(token -> token.subject("client")
                        .claim("accountId", CLIENT_ID.toString())
                        .claim("role", "CLIENT"))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private void insertAccount(UUID id, String username, String displayName, String role) {
        jdbcTemplate.update("""
                INSERT INTO app_account (
                    id, username, password_hash, enabled, role, display_name, phone
                ) VALUES (?, ?, '$2a$10$dummyhash', TRUE, ?, ?, NULL)
                """, id, username, role, displayName);
    }
}
