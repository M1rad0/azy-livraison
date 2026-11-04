package com.azylivraison.delivery.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.azylivraison.planning.domain.RoutingPreset;
import com.azylivraison.routing.service.RoutingNotFoundException;
import com.azylivraison.routing.service.RoutingRouteResult;
import com.azylivraison.routing.service.RoutingService;
import com.azylivraison.routing.service.RoutingUnavailableException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class ExpressDeliveryApiIntegrationTest {

    private static final UUID CLIENT_ID = UUID.fromString("e1000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ID = UUID.fromString("e1000000-0000-0000-0000-000000000002");
    private static final UUID COURIER_ID = UUID.fromString("e1000000-0000-0000-0000-000000000003");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean RoutingService routingService;

    @BeforeEach
    void installActors() {
        jdbcTemplate.update("""
                INSERT INTO app_account (id, username, password_hash, enabled, role, display_name, phone)
                VALUES (?, 'express-client', 'unused', TRUE, 'CLIENT', 'Client express', '0340000000'),
                       (?, 'express-admin', 'unused', TRUE, 'ADMIN', 'Admin express', '0340000001')
                """, CLIENT_ID, ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO client_profile (account_id, express_delivery_enabled)
                VALUES (?, FALSE)
                """, CLIENT_ID);
        jdbcTemplate.update("""
                INSERT INTO courier_profile (
                    id, name, contact, active, vehicle_type, max_weight_kg_override,
                    default_departure_time, departure_location_label,
                    departure_latitude, departure_longitude
                ) VALUES (?, 'Livreur express', '0320000000', TRUE, 'MOTO', 0.5,
                          '06:00:00', 'Analakely', -18.9089, 47.5212)
                """, COURIER_ID);
    }

    @Test
    void requiresClientPermissionButLetsAdministrationCreateExpressRequests() throws Exception {
        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXPRESS_DELIVERY_FORBIDDEN"));

        mockMvc.perform(post("/api/admin/clients/{clientId}/delivery-requests", CLIENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestKind").value("EXPRESS"))
                .andExpect(jsonPath("$.createdByAdministration").value(true));
    }

    @Test
    void confirmsAnExpressRequestWithOneOrMoreCouriersAndWarnsWithoutBlockingCapacity() throws Exception {
        jdbcTemplate.update("UPDATE client_profile SET express_delivery_enabled = TRUE WHERE account_id = ?", CLIENT_ID);
        MvcResult creation = mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestKind").value("EXPRESS"))
                .andExpect(jsonPath("$.expressStatus").value("PENDING"))
                .andReturn();
        String requestId = objectMapper.readTree(creation.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/admin/express-deliveries/{id}/confirm", requestId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courierIds":["%s"],"version":0}
                                """.formatted(COURIER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.expressStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.request.packages[0].status").value("DISPATCHED"))
                .andExpect(jsonPath("$.couriers[0].id").value(COURIER_ID.toString()))
                .andExpect(jsonPath("$.capacityWarning").value(true));

        mockMvc.perform(get("/api/admin/express-deliveries").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].request.id").value(requestId));
    }

    @Test
    void recordsAnExpressPostponementAsFinalWithoutCreatingTheNextRequest() throws Exception {
        jdbcTemplate.update(
                "UPDATE client_profile SET express_delivery_enabled = TRUE WHERE account_id = ?", CLIENT_ID);
        MvcResult creation = mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()))
                .andExpect(status().isCreated())
                .andReturn();
        var created = objectMapper.readTree(creation.getResponse().getContentAsString());
        String requestId = created.get("id").asText();
        String packageId = created.get("packages").get(0).get("id").asText();
        LocalDate serviceDate = LocalDate.parse(created.get("requestedDate").asText());

        mockMvc.perform(post("/api/admin/express-deliveries/{id}/confirm", requestId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courierIds":["%s"],"version":0}
                                """.formatted(COURIER_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/delivery-days/{date}/packages/{id}/outcome",
                        serviceDate, packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"outcome":"POSTPONED","note":"Report manuel à organiser","version":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packages[0].finalOutcome").value("POSTPONED"))
                .andExpect(jsonPath("$.packages[0].carriedForwardToPackageId").doesNotExist())
                .andExpect(jsonPath("$.packages[0].version").value(2));

        Integer successorsBeforeManualAction = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM delivery_package
                WHERE carried_forward_from_package_id = ?
                """, Integer.class, UUID.fromString(packageId));
        org.assertj.core.api.Assertions.assertThat(successorsBeforeManualAction).isZero();

        String expressStatus = jdbcTemplate.queryForObject(
                "SELECT express_status FROM delivery_request WHERE id = ?",
                String.class,
                UUID.fromString(requestId));
        org.assertj.core.api.Assertions.assertThat(expressStatus).isEqualTo("COMPLETED");

        mockMvc.perform(post("/api/admin/delivery-days/{date}/clients/{clientId}/close",
                        serviceDate, CLIENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allClientsClosed").value(true));

        mockMvc.perform(post("/api/admin/delivery-days/{date}/close", serviceDate)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get("/api/admin/delivery-packages/postponed").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].package.requestKind").value("EXPRESS"));
        Long initialFee = jdbcTemplate.queryForObject(
                "SELECT delivery_fee_ariary FROM delivery_package WHERE id = ?", Long.class, UUID.fromString(packageId));
        LocalDate targetDate = serviceDate.plusDays(5);
        MvcResult reprogrammed = mockMvc.perform(post(
                        "/api/admin/delivery-days/{date}/packages/{id}/carry-forward", serviceDate, packageId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetDate":"%s","version":2,
                                 "pickup":{"label":"Nouveau retrait","query":"Analakely, Antananarivo",
                                           "latitude":-18.9089,"longitude":47.5212}}
                                """.formatted(targetDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedDate").value(targetDate.toString()))
                .andReturn();
        var successor = objectMapper.readTree(reprogrammed.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT request_kind FROM delivery_request WHERE id = ?", String.class,
                UUID.fromString(successor.get("deliveryRequestId").asText()))).isEqualTo("NORMAL");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_fee_ariary FROM delivery_package WHERE id = ?", Long.class,
                UUID.fromString(successor.get("packageId").asText()))).isEqualTo(initialFee);
        mockMvc.perform(get("/api/admin/delivery-packages/postponed").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void processesAndClosesAnExpressOnlyDayWithoutCreatingANormalDispatch() throws Exception {
        jdbcTemplate.update(
                "UPDATE client_profile SET express_delivery_enabled = TRUE WHERE account_id = ?", CLIENT_ID);
        MvcResult creation = mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()))
                .andExpect(status().isCreated())
                .andReturn();
        var created = objectMapper.readTree(creation.getResponse().getContentAsString());
        String requestId = created.get("id").asText();
        String packageId = created.get("packages").get(0).get("id").asText();
        String serviceDate = created.get("requestedDate").asText();

        mockMvc.perform(post("/api/admin/express-deliveries/{id}/confirm", requestId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courierIds":["%s"],"returnToPickup":true,"version":0}
                                """.formatted(COURIER_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/delivery-days/{date}", serviceDate).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.admissionsClosed").value(false))
                .andExpect(jsonPath("$.dispatchPlanId").doesNotExist())
                .andExpect(jsonPath("$.packages.length()").value(1))
                .andExpect(jsonPath("$.packages[0].packageId").value(packageId))
                .andExpect(jsonPath("$.packages[0].requestKind").value("EXPRESS"))
                .andExpect(jsonPath("$.packages[0].technicalStatus").value("DISPATCHED"));

        mockMvc.perform(put("/api/admin/delivery-packages/{id}/delivery-fee", packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"MANUAL","manualFeeAriary":4500,
                                 "reason":"Tarif express confirme","version":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalFeeAriary").value(4500))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(put("/api/admin/delivery-days/{date}/packages/{id}/outcome",
                        serviceDate, packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"outcome":"RETURN_TO_SENDER","actualCollectedAriary":0,
                                 "actualCourierId":"%s","note":"Destinataire absent","version":2}
                                """.formatted(COURIER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.packages[0].finalOutcome").value("RETURN_TO_SENDER"))
                .andExpect(jsonPath("$.packages[0].version").value(3));

        mockMvc.perform(post("/api/admin/delivery-days/{date}/clients/{clientId}/close",
                        serviceDate, CLIENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedClientCount").value(1))
                .andExpect(jsonPath("$.allClientsClosed").value(true));

        mockMvc.perform(post("/api/admin/delivery-days/{date}/close", serviceDate)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.admissionsClosed").value(true))
                .andExpect(jsonPath("$.dispatchPlanId").doesNotExist())
                .andExpect(jsonPath("$.packages[0].requestKind").value("EXPRESS"));

        // A return-to-sender is already a final outcome. Its physical hand-back
        // remains confirmable after both the client and the day are closed.
        mockMvc.perform(post("/api/admin/delivery-days/{date}/packages/{id}/return-completed",
                        serviceDate, packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.packages[0].returnCompletedAt").exists());
    }

    @Test
    void exposesDefaultNormalAndExpressPricesWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/public/pricing/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].requestKind").value("NORMAL"))
                .andExpect(jsonPath("$[0].distanceMode").value("AIR_DISTANCE"))
                .andExpect(jsonPath("$[1].requestKind").value("EXPRESS"));

        mockMvc.perform(post("/api/public/pricing/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKind":"EXPRESS",
                                  "destination":{"label":"Analakely","latitude":-18.9089,"longitude":47.5212}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestKind").value("EXPRESS"))
                .andExpect(jsonPath("$.distanceMode").value("AIR_DISTANCE"))
                .andExpect(jsonPath("$.distanceFromCenterMeters").isNumber())
                .andExpect(jsonPath("$.deliveryFeeAriary").value(3000));
    }

    @Test
    void calculatesRoadPricingThroughMainRoadRoutingAndPersistsTheDistanceSnapshot() throws Exception {
        when(routingService.route(anyList())).thenReturn(new RoutingRouteResult(
                "OSRM", RoutingPreset.MAIN_ROADS, 2_900, 600, null,
                java.util.List.of(), java.util.List.of()));

        mockMvc.perform(put("/api/admin/pricing-policies")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientAccountId":"%s",
                                  "requestKind":"EXPRESS",
                                  "distanceMode":"ROAD_DISTANCE",
                                  "center":{"label":"Analakely","latitude":-18.9089,"longitude":47.5212},
                                  "defaultWeightKg":1,
                                  "zones":[
                                    {"name":"Centre","maxDistanceMeters":2500,"deliveryFeeAriary":3000},
                                    {"name":"Peripherie","maxDistanceMeters":5000,"deliveryFeeAriary":4000}
                                  ]
                                }
                                """.formatted(CLIENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceMode").value("ROAD_DISTANCE"));

        mockMvc.perform(post("/api/admin/clients/{clientId}/delivery-pricing/quote", CLIENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKind":"EXPRESS",
                                  "destination":{"label":"Ampasampito","latitude":-18.8957,"longitude":47.5458}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceMode").value("ROAD_DISTANCE"))
                .andExpect(jsonPath("$.distanceFromCenterMeters").value(2900))
                .andExpect(jsonPath("$.pricingZoneName").value("Peripherie"))
                .andExpect(jsonPath("$.deliveryFeeAriary").value(4000));

        MvcResult creation = mockMvc.perform(post("/api/admin/clients/{clientId}/delivery-requests", CLIENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packages[0].pricingDistanceMode").value("ROAD_DISTANCE"))
                .andExpect(jsonPath("$.packages[0].distanceFromCenterMeters").value(2900))
                .andReturn();
        UUID requestId = UUID.fromString(objectMapper.readTree(creation.getResponse().getContentAsString())
                .get("id").asText());
        String storedMode = jdbcTemplate.queryForObject("""
                SELECT p.pricing_distance_mode
                FROM delivery_package p
                WHERE p.delivery_request_id = ?
                """, String.class, requestId);
        org.assertj.core.api.Assertions.assertThat(storedMode).isEqualTo("ROAD_DISTANCE");
    }

    @Test
    void exposesRoadPricingRoutingFailuresWithoutFallingBackToAirDistance() throws Exception {
        jdbcTemplate.update("UPDATE delivery_pricing_policy SET distance_mode = 'ROAD_DISTANCE' WHERE id = 2");
        when(routingService.route(anyList())).thenThrow(new RoutingNotFoundException("Trajet introuvable."));

        mockMvc.perform(post("/api/public/pricing/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKind":"EXPRESS",
                                  "destination":{"label":"Destination","latitude":-18.90,"longitude":47.54}
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));

        reset(routingService);
        when(routingService.route(anyList())).thenThrow(new RoutingUnavailableException("Routage indisponible."));
        mockMvc.perform(post("/api/public/pricing/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKind":"EXPRESS",
                                  "destination":{"label":"Destination","latitude":-18.90,"longitude":47.54}
                                }
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ROUTING_UNAVAILABLE"));
    }

    @Test
    void savesAndAppliesACompleteClientPricingGridClonedFromTheDefaultPolicy() throws Exception {
        mockMvc.perform(put("/api/admin/pricing-policies")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientAccountId":"%s",
                                  "requestKind":"EXPRESS",
                                  "center":{"label":"Analakely","destinationQuery":"Analakely","latitude":-18.9089,"longitude":47.5212},
                                  "defaultWeightKg":1,
                                  "zones":[{"id":"72000000-0000-0000-0000-000000000001","name":"Centre","maxDistanceMeters":5000,"deliveryFeeAriary":3500}],
                                  "weightTiers":[{"maxWeightKg":5,"surchargeAriary":700}],
                                  "timeSlots":[{"label":"Soir","startTime":"18:00","endTime":"19:00","surchargeAriary":900}]
                                }
                                """.formatted(CLIENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSpecific").value(true))
                .andExpect(jsonPath("$.requestKind").value("EXPRESS"))
                .andExpect(jsonPath("$.zones[0].id").value(not("72000000-0000-0000-0000-000000000001")));

        mockMvc.perform(post("/api/admin/clients/{clientId}/delivery-pricing/quote", CLIENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKind":"EXPRESS",
                                  "destination":{"label":"Analakely","latitude":-18.9089,"longitude":47.5212},
                                  "weightKg":2,
                                  "desiredDeliveryTime":"18:30"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseDeliveryFeeAriary").value(3500))
                .andExpect(jsonPath("$.weightSurchargeAriary").value(700))
                .andExpect(jsonPath("$.desiredDeliveryTime").value(startsWith("18:30")))
                .andExpect(jsonPath("$.desiredDeliveryEndTime").value(startsWith("19:30")))
                .andExpect(jsonPath("$.desiredDeliveryEndsNextDay").value(false))
                .andExpect(jsonPath("$.timeSurchargeAriary").value(900))
                .andExpect(jsonPath("$.deliveryFeeAriary").value(5100));

        mockMvc.perform(post("/api/admin/clients/{clientId}/delivery-pricing/quote", CLIENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKind":"EXPRESS",
                                  "destination":{"label":"Analakely","latitude":-18.9089,"longitude":47.5212},
                                  "weightKg":6
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_PRICING_INVALID"));

        mockMvc.perform(post("/api/admin/clients/{clientId}/delivery-pricing/quote", CLIENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKind":"EXPRESS",
                                  "destination":{"label":"Analakely","latitude":-18.9089,"longitude":47.5212},
                                  "weightKg":2,
                                  "desiredDeliveryTime":"23:30"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desiredDeliveryTime").value(startsWith("23:30")))
                .andExpect(jsonPath("$.desiredDeliveryEndTime").value(startsWith("00:30")))
                .andExpect(jsonPath("$.desiredDeliveryEndsNextDay").value(true));
    }

    @Test
    void acceptsOneHourWindowsForNormalRequestsAndRequiresAFutureStartForExpress() throws Exception {
        jdbcTemplate.update("UPDATE client_profile SET express_delivery_enabled = TRUE WHERE account_id = ?", CLIENT_ID);
        String currentMinute = LocalTime.now(ZoneId.of("Indian/Antananarivo"))
                .truncatedTo(ChronoUnit.MINUTES)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        String withDesiredTime = expressRequestJson().replace(
                "\"weightKg\":1",
                "\"desiredDeliveryTime\":\"" + currentMinute + "\",\"weightKg\":1");

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withDesiredTime))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_PRICING_INVALID"));

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()
                                .replace("\"requestKind\":\"EXPRESS\"", "\"requestKind\":\"NORMAL\"")
                                .replace("\"weightKg\":1",
                                        "\"desiredDeliveryTime\":\"18:30\",\"weightKg\":1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packages[0].desiredDeliveryTime").value(startsWith("18:30")))
                .andExpect(jsonPath("$.packages[0].desiredDeliveryEndTime").value(startsWith("19:30")))
                .andExpect(jsonPath("$.packages[0].desiredDeliveryEndsNextDay").value(false));
    }

    @Test
    void forcesExpressRequestsToTodayEvenWhenTheClientOmitsOrSendsAnotherDate() throws Exception {
        jdbcTemplate.update("UPDATE client_profile SET express_delivery_enabled = TRUE WHERE account_id = ?", CLIENT_ID);
        String today = LocalDate.now(ZoneId.of("Indian/Antananarivo")).toString();

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson().replace("\"requestedDate\":\"" + today + "\",", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedDate").value(today));

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson().replace(today, "2000-01-01")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedDate").value(today));

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()
                                .replace("\"requestedDate\":\"" + today + "\",", "")
                                .replace("\"requestKind\":\"EXPRESS\"", "\"requestKind\":\"NORMAL\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_REQUEST_INVALID"));
    }

    @Test
    void appliesAndSnapshotsTheHourlySurchargeForANormalRequest() throws Exception {
        mockMvc.perform(put("/api/admin/pricing-policies")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientAccountId":"%s",
                                  "requestKind":"NORMAL",
                                  "center":{"label":"Analakely","latitude":-18.9089,"longitude":47.5212},
                                  "defaultWeightKg":1,
                                  "zones":[{"name":"Centre","maxDistanceMeters":5000,"deliveryFeeAriary":3500}],
                                  "timeSlots":[{"label":"Soir","startTime":"18:00","endTime":"20:00","surchargeAriary":900}]
                                }
                                """.formatted(CLIENT_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()
                                .replace("\"requestKind\":\"EXPRESS\"", "\"requestKind\":\"NORMAL\"")
                                .replace("\"weightKg\":1",
                                        "\"desiredDeliveryTime\":\"18:30\",\"weightKg\":1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packages[0].baseDeliveryFeeAriary").value(3500))
                .andExpect(jsonPath("$.packages[0].timeSurchargeAriary").value(900))
                .andExpect(jsonPath("$.packages[0].deliveryFeeAriary").value(4400))
                .andExpect(jsonPath("$.packages[0].desiredDeliveryTime").value(startsWith("18:30")))
                .andExpect(jsonPath("$.packages[0].desiredDeliveryEndTime").value(startsWith("19:30")));
    }

    private String expressRequestJson() {
        String today = LocalDate.now(ZoneId.of("Indian/Antananarivo")).toString();
        return """
                {
                  "requestedDate":"%s",
                  "requestKind":"EXPRESS",
                  "pickup":{"label":"Boutique","latitude":-18.9089,"longitude":47.5212},
                  "packages":[{
                    "recipientName":"Destinataire",
                    "faritra":false,
                    "destination":{"label":"Ampasampito","latitude":-18.8957,"longitude":47.5458},
                    "contact":"0320000002",
                    "amountToCollectAriary":20000,
                    "mobileMoneyPaid":false,
                    "weightKg":1
                  }]
                }
                """.formatted(today);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor client() {
        return jwt().jwt(token -> token.subject("express-client")
                        .claim("accountId", CLIENT_ID.toString()).claim("role", "CLIENT"))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("express-admin")
                        .claim("accountId", ADMIN_ID.toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
