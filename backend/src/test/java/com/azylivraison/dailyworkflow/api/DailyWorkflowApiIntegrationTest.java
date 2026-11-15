package com.azylivraison.dailyworkflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DailyWorkflowApiIntegrationTest {

    private static final UUID CLIENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_CLIENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID PLANNED_COURIER_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID ACTUAL_COURIER_ID = UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final LocalDate SERVICE_DATE = LocalDate.of(2099, 8, 26);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpAccounts() {
        insertAccount(CLIENT_ID, "workflow-client", "Client workflow", "CLIENT");
        insertAccount(OTHER_CLIENT_ID, "workflow-other", "Autre client", "CLIENT");
        insertAccount(ADMIN_ID, "workflow-admin", "Admin workflow", "ADMIN");
        insertCourier(PLANNED_COURIER_ID, "Livreur prévu", true);
        insertCourier(ACTUAL_COURIER_ID, "Livreur réel", false);
    }

    @Test
    void plannedCashPayoutsRejectNonIntegerMoneyAtTheRealHttpBoundary() throws Exception {
        long previousBatches = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch", Long.class);
        long previousMovements = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cash_ledger_movement", Long.class);
        String body = """
                {
                  "planId": "%s",
                  "idempotencyKey": "strict-http-integer",
                  "expectedLedgerRevision": 0,
                  "expectedBatchId": null,
                  "transfers": [{"sellerAccountId": "%s", "courierAccountId": "%s", "amountAriary": %s}]
                }
                """;
        UUID missingPlan = UUID.randomUUID();
        for (String amount : List.of("1.5", "1.0", "\"1500\"", "null")) {
            mockMvc.perform(post("/api/admin/cash-briefings/manual")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.formatted(missingPlan, CLIENT_ID, PLANNED_COURIER_ID, amount)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch", Long.class))
                .isEqualTo(previousBatches);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cash_ledger_movement", Long.class))
                .isEqualTo(previousMovements);

        String integer = body.formatted(missingPlan, CLIENT_ID, PLANNED_COURIER_ID, "1500");
        mockMvc.perform(post("/api/admin/cash-briefings/manual")
                        .with(admin()).contentType(MediaType.APPLICATION_JSON).content(integer))
                .andExpect(status().isConflict()); // Integer accepted; the missing dispatch is the business error.
        mockMvc.perform(post("/api/admin/cash-briefings/manual")
                        .with(client(CLIENT_ID)).contentType(MediaType.APPLICATION_JSON).content(integer))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch", Long.class))
                .isEqualTo(previousBatches);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cash_ledger_movement", Long.class))
                .isEqualTo(previousMovements);
    }

    @Test
    void clientAndAdministratorCanCancelOnlyPendingOwnedPackagesAndCapacityIsFreed() throws Exception {
        jdbcTemplate.update("UPDATE operational_settings SET daily_package_limit = 1 WHERE id = 1");
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 1);
        UUID packageId = UUID.fromString(created.get("packages").get(0).get("id").asText());

        mockMvc.perform(post("/api/client/delivery-packages/{id}/cancel", packageId)
                        .with(client(OTHER_CLIENT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/client/delivery-packages/{id}/cancel", packageId)
                        .with(client(CLIENT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/client/delivery-requests/{id}", created.get("id").asText())
                        .with(client(CLIENT_ID)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("status", "CANCELLED")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        JsonNode replacement = createRequest(OTHER_CLIENT_ID, SERVICE_DATE, 1);
        assertThat(replacement.get("packages").size()).isEqualTo(1);

        UUID replacementId = UUID.fromString(replacement.get("packages").get(0).get("id").asText());
        mockMvc.perform(post("/api/admin/delivery-packages/{id}/cancel", replacementId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isNoContent());

        String outcome = jdbcTemplate.queryForObject(
                "SELECT final_outcome FROM delivery_package WHERE id = ?", String.class, replacementId);
        assertThat(outcome).isNull();

        mockMvc.perform(get("/api/admin/queue")
                        .param("date", SERVICE_DATE.toString())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.pendingRequestCount").value(0))
                .andExpect(jsonPath("$.stats.pendingPackageCount").value(0));
    }

    @Test
    void aGeneratedPlanLocksItsDateAgainstNewRequestsAndSecondPlans() throws Exception {
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 1);
        UUID packageId = UUID.fromString(created.get("packages").get(0).get("id").asText());
        installPlan(SERVICE_DATE, List.of(packageId));

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(OTHER_CLIENT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(SERVICE_DATE, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_DATE_CLOSED"));

        mockMvc.perform(post("/api/client/delivery-packages/{id}/cancel", packageId)
                        .with(client(CLIENT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void recordsEveryFinalOutcomeCarriesPostponedPackagesAndClosesThenReopensTheDay() throws Exception {
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 4);
        UUID deliveredId = packageId(created, 0);
        UUID postponedId = packageId(created, 1);
        UUID returnId = packageId(created, 2);
        UUID cancelledId = packageId(created, 3);

        jdbcTemplate.update(
                "UPDATE delivery_package SET desired_delivery_time = '18:30:00' WHERE id = ?",
                postponedId);

        mockMvc.perform(post("/api/admin/delivery-packages/{id}/cancel", cancelledId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isNoContent());

        installPlan(SERVICE_DATE, List.of(deliveredId, postponedId, returnId));

        MvcResult delivered = updateOutcome(deliveredId, "DELIVERED", null, 1);
        assertThat(packageNode(delivered, deliveredId).get("actualCollectedAriary").asLong())
                .isEqualTo(10_000);
        assertThat(packageNode(delivered, deliveredId).get("distributorCourierId").asText())
                .isEqualTo(PLANNED_COURIER_ID.toString());
        assertThat(packageNode(delivered, deliveredId).get("actualCourierId").asText())
                .isEqualTo(ACTUAL_COURIER_ID.toString());
        assertThat(packageNode(delivered, deliveredId).get("actualCourierName").asText())
                .isEqualTo("Livreur réel");
        assertThat(packageNode(delivered, deliveredId).get("pricingDistanceMode").asText())
                .isEqualTo("AIR_DISTANCE");
        assertThat(packageNode(delivered, deliveredId).get("distanceFromCenterMeters").asLong())
                .isPositive();

        jdbcTemplate.update(
                "UPDATE courier_profile SET name = 'Livreur réel renommé' WHERE id = ?",
                ACTUAL_COURIER_ID);
        MvcResult corrected = updateOutcome(deliveredId, "DELIVERED", null, 2);
        assertThat(packageNode(corrected, deliveredId).get("actualCourierName").asText())
                .as("une correction conserve l'identité enregistrée au moment de la livraison")
                .isEqualTo("Livreur réel");

        updateOutcome(postponedId, "POSTPONED", null, 1);
        updateOutcome(returnId, "RETURN_TO_SENDER", 0L, 1);

        closeClient(CLIENT_ID, 0);

        MvcResult carried = mockMvc.perform(post(
                        "/api/admin/delivery-days/{date}/packages/{id}/carry-forward",
                        SERVICE_DATE, postponedId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": 2,
                                  "pickup": {
                                    "label": "Colis gardé à Ankorondrano",
                                    "query": "Ankorondrano, Antananarivo",
                                    "latitude": -18.8792,
                                    "longitude": 47.5215
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedDate").value("2099-08-27"))
                .andReturn();
        UUID nextPackageId = UUID.fromString(
                objectMapper.readTree(carried.getResponse().getContentAsString()).get("packageId").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT carried_forward_from_package_id FROM delivery_package WHERE id = ?",
                UUID.class,
                nextPackageId)).isEqualTo(postponedId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pricing_distance_mode FROM delivery_package WHERE id = ?",
                String.class,
                nextPackageId)).isEqualTo("AIR_DISTANCE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT desired_delivery_time FROM delivery_package WHERE id = ?",
                LocalTime.class,
                nextPackageId)).isEqualTo(LocalTime.of(18, 30));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pricing_policy_id FROM delivery_package WHERE id = ?",
                Short.class,
                nextPackageId)).isEqualTo((short) 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT base_delivery_fee_ariary FROM delivery_package WHERE id = ?",
                Long.class,
                nextPackageId)).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT weight_surcharge_ariary FROM delivery_package WHERE id = ?",
                Long.class,
                nextPackageId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT time_surcharge_ariary FROM delivery_package WHERE id = ?",
                Long.class,
                nextPackageId)).isZero();

        mockMvc.perform(put("/api/admin/delivery-days/{date}/packages/{id}/outcome",
                        SERVICE_DATE, postponedId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"DELIVERED\",\"version\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Ce client est déjà clôturé pour cette journée. Ses informations sont désormais figées."));

        MvcResult beforeClose = mockMvc.perform(get("/api/admin/delivery-days/{date}", SERVICE_DATE)
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.total").value(3))
                .andExpect(jsonPath("$.counts.finalized").value(3))
                .andExpect(jsonPath("$.counts.cancelled").value(0))
                .andExpect(jsonPath("$.counts.postponed").value(1))
                .andExpect(jsonPath("$.counts.returnToSender").value(1))
                .andExpect(jsonPath("$.totals.recoveredAriary").value(10_000))
                .andReturn();
        JsonNode day = objectMapper.readTree(beforeClose.getResponse().getContentAsString());
        long chargedFees = day.get("totals").get("deliveryFeesAriary").asLong();
        assertThat(day.get("totals").get("amountToRemitAriary").asLong())
                .isEqualTo(10_000 - chargedFees);
        assertThat(chargedFees).isPositive();

        long versionAfterClientClosure = day.get("version").asLong();

        mockMvc.perform(get("/api/client/reports/{date}", SERVICE_DATE)
                        .with(client(CLIENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientDisplayName").value("Client workflow"))
                .andExpect(jsonPath("$.totals.packageCount").value(3));

        MvcResult closed = mockMvc.perform(post("/api/admin/delivery-days/{date}/close", SERVICE_DATE)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + versionAfterClientClosure + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closureRevision").value(2))
                .andReturn();

        long returnVersion = packageNode(closed, returnId).get("version").asLong();
        MvcResult returned = mockMvc.perform(post(
                        "/api/admin/delivery-days/{date}/packages/{id}/return-completed",
                        SERVICE_DATE, returnId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + returnVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.returnsCompleted").value(1))
                .andReturn();

        long completedReturnVersion = packageNode(returned, returnId).get("version").asLong();
        mockMvc.perform(post(
                        "/api/admin/delivery-days/{date}/packages/{id}/return-completed",
                        SERVICE_DATE, returnId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + completedReturnVersion + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Le retour de ce colis a déjà été confirmé."));

        long closedVersion = objectMapper.readTree(returned.getResponse().getContentAsString())
                .get("version").asLong();
        mockMvc.perform(post("/api/admin/delivery-days/{date}/reopen", SERVICE_DATE)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + closedVersion
                                + ",\"reason\":\"Correction du compte rendu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.reopenReason").value("Correction du compte rendu"));
    }

    @Test
    void refusesClosureUntilEveryPlannedPackageHasAFinalOutcome() throws Exception {
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 1);
        UUID packageId = packageId(created, 0);
        installPlan(SERVICE_DATE, List.of(packageId));

        long version = jdbcTemplate.queryForObject(
                "SELECT version FROM delivery_operation_day WHERE service_date = ?",
                Long.class,
                SERVICE_DATE);
        mockMvc.perform(post("/api/admin/delivery-days/{date}/close", SERVICE_DATE)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Attribuez un état final aux 1 colis restant avant la clôture."));
    }

    @Test
    void postponedListKeepsEachClientsClosedIdentityAfterAnotherClientCloses() throws Exception {
        UUID firstPackage = packageId(createRequest(CLIENT_ID, SERVICE_DATE, 1), 0);
        UUID secondPackage = packageId(createRequest(OTHER_CLIENT_ID, SERVICE_DATE, 1), 0);
        installPlan(SERVICE_DATE, List.of(firstPackage, secondPackage));
        updateOutcome(firstPackage, "POSTPONED", null, 1);
        updateOutcome(secondPackage, "POSTPONED", null, 1);
        long dayVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM delivery_operation_day WHERE service_date = ?", Long.class, SERVICE_DATE);
        MvcResult firstClosed = mockMvc.perform(post(
                        "/api/admin/delivery-days/{date}/clients/{clientId}/close", SERVICE_DATE, CLIENT_ID)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + dayVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allClientsClosed").value(false))
                .andReturn();
        long firstClosedVersion = objectMapper.readTree(firstClosed.getResponse().getContentAsString())
                .get("dayVersion").asLong();
        closeClient(OTHER_CLIENT_ID, firstClosedVersion);
        jdbcTemplate.update("UPDATE app_account SET display_name = 'Nouveau nom', phone = '0999999999' WHERE id = ?",
                CLIENT_ID);

        mockMvc.perform(get("/api/admin/delivery-packages/postponed")
                        .param("query", "Client workflow").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].package.ownerDisplayName").value("Client workflow"));
    }

    @Test
    void closesWithoutSchedulingPostponedPackagesAndReprogramsAfterwardToAChosenDate() throws Exception {
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 1);
        UUID packageId = packageId(created, 0);
        installPlan(SERVICE_DATE, List.of(packageId));
        updateOutcome(packageId, "POSTPONED", null, 1);

        mockMvc.perform(get("/api/admin/delivery-packages/postponed").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/admin/delivery-days/{date}/packages/{id}/carry-forward", SERVICE_DATE, packageId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(carryForwardJson(SERVICE_DATE.plusDays(5), 2)))
                .andExpect(status().isConflict());

        long version = jdbcTemplate.queryForObject(
                "SELECT version FROM delivery_operation_day WHERE service_date = ?",
                Long.class,
                SERVICE_DATE);
        long clientClosedVersion = closeClient(CLIENT_ID, version);
        mockMvc.perform(get("/api/admin/delivery-packages/postponed").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].serviceDate").value(SERVICE_DATE.toString()))
                .andExpect(jsonPath("$[0].package.packageId").value(packageId.toString()));

        mockMvc.perform(post("/api/admin/delivery-days/{date}/close", SERVICE_DATE)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + clientClosedVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        String reportBefore = mockMvc.perform(get("/api/client/reports/{date}", SERVICE_DATE).with(client(CLIENT_ID)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        LocalDate targetDate = SERVICE_DATE.plusDays(5);
        MvcResult carried = mockMvc.perform(post("/api/admin/delivery-days/{date}/packages/{id}/carry-forward",
                        SERVICE_DATE, packageId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(carryForwardJson(targetDate, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedDate").value(targetDate.toString()))
                .andReturn();
        UUID successor = UUID.fromString(objectMapper.readTree(carried.getResponse().getContentAsString())
                .get("packageId").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT priority_at FROM delivery_package WHERE id = ?", java.sql.Timestamp.class, successor))
                .isEqualTo(jdbcTemplate.queryForObject(
                        "SELECT priority_at FROM delivery_package WHERE id = ?", java.sql.Timestamp.class, packageId));
        assertThat(jdbcTemplate.queryForObject("SELECT created_by_account_id FROM delivery_request WHERE id = ?",
                UUID.class, UUID.fromString(objectMapper.readTree(carried.getResponse().getContentAsString())
                        .get("deliveryRequestId").asText()))).isEqualTo(ADMIN_ID);

        mockMvc.perform(get("/api/admin/delivery-packages/postponed").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/admin/delivery-days/{date}/packages/{id}/carry-forward", SERVICE_DATE, packageId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(carryForwardJson(targetDate.plusDays(1), 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ce colis a déjà été reprogrammé."));

        JsonNode before = objectMapper.readTree(reportBefore);
        JsonNode after = objectMapper.readTree(mockMvc.perform(get("/api/client/reports/{date}", SERVICE_DATE)
                        .with(client(CLIENT_ID))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(after.get("reportId")).isEqualTo(before.get("reportId"));
        assertThat(after.get("totals")).isEqualTo(before.get("totals"));

        long closedVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM delivery_operation_day WHERE service_date = ?", Long.class, SERVICE_DATE);
        mockMvc.perform(post("/api/admin/delivery-days/{date}/reopen", SERVICE_DATE)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + closedVersion + ",\"reason\":\"Contrôle\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/admin/delivery-days/{date}/packages/{id}/outcome", SERVICE_DATE, packageId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2,\"outcome\":\"CANCELLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Ce report a déjà créé un colis reprogrammé. Son état ne peut plus être remplacé."));
    }

    @Test
    void rejectsPastDatesCapacityOverflowAndClosedTargetDaysWhenRescheduling() throws Exception {
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 1);
        UUID packageId = packageId(created, 0);
        installPlan(SERVICE_DATE, List.of(packageId));
        updateOutcome(packageId, "POSTPONED", null, 1);
        closeClient(CLIENT_ID, 0);

        for (LocalDate invalidDate : List.of(SERVICE_DATE, SERVICE_DATE.minusDays(1))) {
            mockMvc.perform(post("/api/admin/delivery-days/{date}/packages/{id}/carry-forward", SERVICE_DATE, packageId)
                            .with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(carryForwardJson(invalidDate, 2)))
                    .andExpect(status().isUnprocessableEntity());
        }
        LocalDate fullDate = SERVICE_DATE.plusDays(3);
        createRequest(OTHER_CLIENT_ID, fullDate, 1);
        jdbcTemplate.update("UPDATE operational_settings SET daily_package_limit = 1 WHERE id = 1");
        mockMvc.perform(post("/api/admin/delivery-days/{date}/packages/{id}/carry-forward", SERVICE_DATE, packageId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(carryForwardJson(fullDate, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DAILY_CAPACITY_EXCEEDED"));
        LocalDate closedDate = SERVICE_DATE.plusDays(4);
        jdbcTemplate.update("INSERT INTO delivery_operation_day(service_date,status,admissions_closed_at) VALUES (?, 'OPEN', CURRENT_TIMESTAMP)",
                closedDate);
        mockMvc.perform(post("/api/admin/delivery-days/{date}/packages/{id}/carry-forward", SERVICE_DATE, packageId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(carryForwardJson(closedDate, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_DATE_CLOSED"));
        mockMvc.perform(get("/api/admin/delivery-packages/postponed").param("query", "workflow").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/admin/delivery-packages/postponed").param("query", "introuvable").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/admin/delivery-packages/postponed").with(client(CLIENT_ID)))
                .andExpect(status().isForbidden());
    }

    private String carryForwardJson(LocalDate targetDate, long version) {
        return """
                {"targetDate":"%s","version":%d,
                 "pickup":{"label":"Retrait de reprise","query":"Analakely, Antananarivo",
                           "latitude":-18.9089,"longitude":47.5212}}
                """.formatted(targetDate, version);
    }

    @Test
    void reschedulingPreservesTheAnnouncedManualTariffAndItsOriginalCalculation() throws Exception {
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 1);
        UUID packageId = packageId(created, 0);
        Long calculatedFee = jdbcTemplate.queryForObject(
                "SELECT delivery_fee_ariary FROM delivery_package WHERE id = ?", Long.class, packageId);
        installPlan(SERVICE_DATE, List.of(packageId));
        mockMvc.perform(put("/api/admin/delivery-packages/{id}/delivery-fee", packageId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"MANUAL","manualFeeAriary":12345,
                                 "reason":"Prix annoncé au client","version":1}
                                """))
                .andExpect(status().isOk());
        updateOutcome(packageId, "POSTPONED", null, 2);
        closeClient(CLIENT_ID, 0);
        MvcResult carried = mockMvc.perform(post(
                        "/api/admin/delivery-days/{date}/packages/{id}/carry-forward", SERVICE_DATE, packageId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(carryForwardJson(SERVICE_DATE.plusDays(6), 3)))
                .andExpect(status().isOk()).andReturn();
        UUID successor = UUID.fromString(objectMapper.readTree(carried.getResponse().getContentAsString())
                .get("packageId").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_fee_ariary FROM delivery_package WHERE id = ?", Long.class, successor))
                .isEqualTo(calculatedFee);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT final_delivery_fee_ariary FROM delivery_package WHERE id = ?", Long.class, successor))
                .isEqualTo(12345L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_fee_override_reason FROM delivery_package WHERE id = ?", String.class, successor))
                .isEqualTo("Prix annoncé au client");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_fee_overridden_by_account_id FROM delivery_package WHERE id = ?", UUID.class, successor))
                .isEqualTo(ADMIN_ID);
    }

    @Test
    void requiresAKnownActualCourierForEveryChargedOutcome() throws Exception {
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 1);
        UUID packageId = packageId(created, 0);
        installPlan(SERVICE_DATE, List.of(packageId));

        mockMvc.perform(put("/api/admin/delivery-days/{date}/packages/{id}/outcome",
                        SERVICE_DATE, packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"DELIVERED\",\"version\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Indiquez le livreur réellement intervenu pour ce colis."));

        mockMvc.perform(put("/api/admin/delivery-days/{date}/packages/{id}/outcome",
                        SERVICE_DATE, packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"RETURN_TO_SENDER\",\"actualCourierId\":\""
                                + UUID.randomUUID() + "\",\"version\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Le livreur réellement intervenu est introuvable."));

        mockMvc.perform(put("/api/admin/delivery-days/{date}/packages/{id}/outcome",
                        SERVICE_DATE, packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"POSTPONED\",\"actualCourierId\":\""
                                + ACTUAL_COURIER_ID + "\",\"version\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Un colis reporté ne doit pas avoir de livreur réel pour cette journée."));
    }

    @Test
    void refusesClosureOfHistoricalChargedOutcomesWithoutAnActualCourier() throws Exception {
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 1);
        UUID packageId = packageId(created, 0);
        installPlan(SERVICE_DATE, List.of(packageId));
        jdbcTemplate.update("""
                UPDATE delivery_package
                SET final_outcome = 'DELIVERED', actual_collected_ariary = amount_to_collect_ariary,
                    outcome_recorded_at = CURRENT_TIMESTAMP,
                    outcome_recorded_by_account_id = ?, version = version + 1
                WHERE id = ?
                """, ADMIN_ID, packageId);

        long dayVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM delivery_operation_day WHERE service_date = ?",
                Long.class,
                SERVICE_DATE);
        mockMvc.perform(post("/api/admin/delivery-days/{date}/close", SERVICE_DATE)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + dayVersion + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Indiquez le livreur réellement intervenu pour 1 colis facturé avant la clôture."));
    }

    @Test
    void refusesToCloseAnOpenDayWhileANormalPackageStillNeedsDispatching() throws Exception {
        createRequest(CLIENT_ID, SERVICE_DATE, 1);
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day (
                    service_date, status, admissions_closed_at, dispatch_plan_id
                ) VALUES (?, 'OPEN', NULL, NULL)
                """, SERVICE_DATE);

        mockMvc.perform(post("/api/admin/delivery-days/{date}/close", SERVICE_DATE)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Générez le plan normal avant la clôture : 1 colis est encore en attente."));
    }

    @Test
    void keepsCalculatedFeeAndLetsAdminOverrideOrRestoreTheFinalFee() throws Exception {
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 1);
        UUID packageId = packageId(created, 0);
        Long calculatedFee = jdbcTemplate.queryForObject(
                "SELECT delivery_fee_ariary FROM delivery_package WHERE id = ?",
                Long.class,
                packageId);
        assertThat(calculatedFee).isNotNull();
        installPlan(SERVICE_DATE, List.of(packageId));
        long announcedFee = calculatedFee + 1_250;

        mockMvc.perform(put("/api/admin/delivery-packages/{id}/delivery-fee", packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"MANUAL","manualFeeAriary":%d,
                                 "reason":"Tarif annoncé au client","version":1}
                                """.formatted(announcedFee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MANUAL"))
                .andExpect(jsonPath("$.calculatedFeeAriary").value(calculatedFee))
                .andExpect(jsonPath("$.finalFeeAriary").value(announcedFee))
                .andExpect(jsonPath("$.feeVersion").value(1))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(put("/api/admin/delivery-packages/{id}/delivery-fee", packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"CALCULATED\",\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("CALCULATED"))
                .andExpect(jsonPath("$.finalFeeAriary").value(calculatedFee))
                .andExpect(jsonPath("$.overrideReason").doesNotExist())
                .andExpect(jsonPath("$.feeVersion").value(2))
                .andExpect(jsonPath("$.version").value(3));

        mockMvc.perform(put("/api/admin/delivery-packages/{id}/delivery-fee", packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"MANUAL","manualFeeAriary":%d,
                                 "reason":"Confirmation du prix annoncé","version":3}
                                """.formatted(announcedFee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));

        MvcResult outcome = updateOutcome(packageId, "DELIVERED", 10_000L, 4);
        JsonNode day = objectMapper.readTree(outcome.getResponse().getContentAsString());
        assertThat(day.get("totals").get("deliveryFeesAriary").asLong()).isEqualTo(announcedFee);
        assertThat(packageNode(outcome, packageId).get("calculatedDeliveryFeeAriary").asLong())
                .isEqualTo(calculatedFee);
        assertThat(packageNode(outcome, packageId).get("finalDeliveryFeeAriary").asLong())
                .isEqualTo(announcedFee);

        long dayVersion = day.get("version").asLong();
        long versionAfterClientClosure = closeClient(CLIENT_ID, dayVersion);
        mockMvc.perform(post("/api/admin/delivery-days/{date}/close", SERVICE_DATE)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + versionAfterClientClosure + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/delivery-packages/{id}/delivery-fee", packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"CALCULATED\",\"version\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Rouvrez la journée avant de corriger les frais de livraison."));

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_fee_change_event WHERE delivery_package_id = ?",
                Integer.class,
                packageId);
        assertThat(eventCount).isEqualTo(3);
    }

    @Test
    void refusesCalculatedModeWhenThePackageHasNoAutomaticFee() throws Exception {
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 1);
        UUID packageId = packageId(created, 0);
        installPlan(SERVICE_DATE, List.of(packageId));
        jdbcTemplate.update("""
                UPDATE delivery_package
                SET pricing_zone_id = NULL,
                    pricing_zone_name = NULL,
                    delivery_fee_ariary = NULL,
                    final_delivery_fee_ariary = NULL,
                    distance_from_center_meters = NULL,
                    pricing_distance_mode = NULL,
                    pricing_zone_max_distance_meters = NULL,
                    pricing_policy_version = NULL
                WHERE id = ?
                """, packageId);

        mockMvc.perform(put("/api/admin/delivery-packages/{id}/delivery-fee", packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"CALCULATED\",\"version\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Aucun tarif calculé n'est disponible pour ce colis. Indiquez un tarif manuel."));
    }

    @Test
    void followsActualCashWithoutCommissionAndPaysTheSellerThroughTheNewRegister() throws Exception {
        UUID packageId = packageId(createRequest(CLIENT_ID, SERVICE_DATE, 1), 0);
        jdbcTemplate.update("""
                UPDATE delivery_package SET amount_to_collect_ariary = 30000,
                    delivery_fee_ariary = 5000, final_delivery_fee_ariary = 5000 WHERE id = ?
                """, packageId);
        installPlan(SERVICE_DATE, List.of(packageId));
        updateOutcome(packageId, "DELIVERED", 30_000L, 1);
        assertThat(cashBalance("COURIER:" + ACTUAL_COURIER_ID)).isEqualTo(30_000);
        assertThat(cashBalance("COURIER:" + PLANNED_COURIER_ID)).isZero();
        updateOutcome(packageId, "DELIVERED", 30_000L, 2);
        assertThat(cashBalance("COURIER:" + ACTUAL_COURIER_ID)).isEqualTo(30_000);
        closeClient(CLIENT_ID, 0);
        assertThat(cashBalance("SELLER:" + CLIENT_ID)).isEqualTo(25_000);

        String transfer = objectMapper.writeValueAsString(java.util.Map.of(
                "idempotencyKey", "workflow-cash-" + packageId,
                "fromKey", "COURIER:" + ACTUAL_COURIER_ID,
                "toKey", "SELLER:" + CLIENT_ID,
                "amountAriary", 25_000,
                "paymentMethod", "MVOLA"));
        MvcResult paid = mockMvc.perform(post("/api/admin/cash-ledger/transfers").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(transfer))
                .andExpect(status().is2xxSuccessful()).andReturn();
        UUID movementId = UUID.fromString(objectMapper.readTree(paid.getResponse().getContentAsString()).path("id").asText());
        assertThat(cashBalance("COURIER:" + ACTUAL_COURIER_ID)).isEqualTo(5_000);
        assertThat(cashBalance("SELLER:" + CLIENT_ID)).isZero();
        assertThat(cashBalance("AZY")).isZero();
        mockMvc.perform(get("/api/client/finance").with(client(CLIENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netBalanceAriary").value(0))
                .andExpect(jsonPath("$.totalConfirmedSignedPaymentsAriary").value(25_000));
        mockMvc.perform(get("/api/client/finance/payments/{id}/proof", movementId).with(client(OTHER_CLIENT_ID)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/finance/delivery-days/{date}/clients/{id}/courier-compensations/finalize", SERVICE_DATE, CLIENT_ID)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"closureRevision\":1,\"overrides\":[]}"))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM courier_earning", Long.class)).isZero();
        mockMvc.perform(post("/api/admin/delivery-days/{date}/clients/{id}/reopen", SERVICE_DATE, CLIENT_ID)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"Correction\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/admin/cash-ledger/movements/{id}/void", movementId).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Versement non réalisé\"}"))
                .andExpect(status().isOk());
        assertThat(cashBalance("COURIER:" + ACTUAL_COURIER_ID)).isEqualTo(30_000);
        assertThat(cashBalance("SELLER:" + CLIENT_ID)).isEqualTo(25_000);
        mockMvc.perform(get("/api/client/finance").with(client(CLIENT_ID)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.netBalanceAriary").value(25_000));
    }

    private long cashBalance(String accountKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/cash-ledger").with(admin()))
                .andExpect(status().isOk()).andReturn();
        for (JsonNode account : objectMapper.readTree(result.getResponse().getContentAsString()).path("accounts")) {
            if (accountKey.equals(account.path("key").asText())) return account.path("balanceAriary").asLong();
        }
        throw new AssertionError("Compte de caisse absent : " + accountKey);
    }

    private MvcResult updateOutcome(UUID packageId, String outcome, Long actual, long version) throws Exception {
        String actualJson = actual == null ? "null" : actual.toString();
        String courierJson = "POSTPONED".equals(outcome)
                ? "null"
                : "\"" + ACTUAL_COURIER_ID + "\"";
        return mockMvc.perform(put("/api/admin/delivery-days/{date}/packages/{id}/outcome",
                        SERVICE_DATE, packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"" + outcome + "\",\"actualCollectedAriary\":"
                                + actualJson + ",\"actualCourierId\":" + courierJson
                                + ",\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private long closeClient(UUID clientAccountId, long dayVersion) throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/admin/delivery-days/{date}/clients/{clientId}/close",
                        SERVICE_DATE, clientAccountId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + dayVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allClientsClosed").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("dayVersion").asLong();
    }

    private JsonNode createRequest(UUID ownerId, LocalDate date, int packageCount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(date, packageCount)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String requestJson(LocalDate date, int packageCount) {
        StringBuilder packages = new StringBuilder();
        for (int index = 0; index < packageCount; index++) {
            if (index > 0) packages.append(',');
            packages.append("""
                    {
                      "recipientName": "Destinataire %d",
                      "faritra": false,
                      "destination": {
                        "label": "Analakely %d",
                        "query": "Analakely, Antananarivo",
                        "latitude": -18.9089,
                        "longitude": 47.5212
                      },
                      "contact": "034 00 000 0%d",
                      "amountToCollectAriary": 10000,
                      "mobileMoneyPaid": %s
                    }
                    """.formatted(index + 1, index + 1, index, index == 0));
        }
        return """
                {
                  "requestedDate": "%s",
                  "pickup": {
                    "label": "Boutique client",
                    "query": "Antaninarenina, Antananarivo",
                    "latitude": -18.9096,
                    "longitude": 47.5236
                  },
                  "packages": [%s]
                }
                """.formatted(date, packages);
    }

    private void installPlan(LocalDate date, List<UUID> packageIds) {
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO dispatch_plan (
                    id, service_date, status, strategy, created_by_account_id,
                    idempotency_key, request_hash,
                    hub_label, hub_latitude, hub_longitude,
                    input_snapshot_json, policy_snapshot_json,
                    selected_package_count, assigned_package_count, unassigned_package_count,
                    selected_courier_count, used_courier_count
                ) VALUES (?, ?, 'READY', 'VIA_HUB', ?, ?, ?,
                          'Siège test', -18.90, 47.52, '{}', '{}', ?, ?, 0, 1, 1)
                """, planId, date, ADMIN_ID, "test-" + planId, "0".repeat(64),
                packageIds.size(), packageIds.size());
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day (
                    service_date, status, admissions_closed_at, dispatch_plan_id
                ) VALUES (?, 'DISPATCHED', CURRENT_TIMESTAMP, ?)
                """, date, planId);
        jdbcTemplate.update("""
                INSERT INTO dispatch_plan_courier (
                    dispatch_plan_id, courier_id, courier_name, courier_contact,
                    vehicle_type, max_weight_kg, average_speed_kmh,
                    departure_at, departure_label, departure_latitude, departure_longitude,
                    used, collection_role, distribution_role
                ) VALUES (?, ?, 'Livreur prévu', '034 00 000 04',
                          'MOTO', 30, 25, CURRENT_TIMESTAMP,
                          'Départ prévu', -18.92, 47.50, TRUE, TRUE, TRUE)
                """, planId, PLANNED_COURIER_ID);
        for (UUID packageId : packageIds) {
            jdbcTemplate.update("""
                    UPDATE delivery_package
                    SET status = 'DISPATCHED', dispatch_plan_id = ?, version = version + 1
                    WHERE id = ?
                    """, planId, packageId);
            jdbcTemplate.update("""
                    INSERT INTO dispatch_package_outcome (
                        dispatch_plan_id, delivery_package_id, delivery_request_id,
                        outcome, collector_courier_id, distributor_courier_id,
                        transferred, estimated_delivery_at, delivery_order,
                        recipient_name, faritra, contact,
                        amount_to_collect_ariary, mobile_money_paid, effective_weight_kg,
                        destination_label, destination_query,
                        destination_latitude, destination_longitude
                    )
                    SELECT ?, package.id, package.delivery_request_id,
                           'ASSIGNED', ?, ?, FALSE, CURRENT_TIMESTAMP, package.sequence_number,
                           package.recipient_name, package.faritra, package.contact,
                           package.amount_to_collect_ariary, package.mobile_money_paid,
                           package.effective_weight_kg, package.destination_label,
                           package.destination_query, package.destination_latitude,
                           package.destination_longitude
                    FROM delivery_package package
                    WHERE package.id = ?
                    """, planId, PLANNED_COURIER_ID, PLANNED_COURIER_ID, packageId);
        }
    }

    private UUID packageId(JsonNode request, int index) {
        return UUID.fromString(request.get("packages").get(index).get("id").asText());
    }

    private JsonNode packageNode(MvcResult result, UUID packageId) throws Exception {
        JsonNode packages = objectMapper.readTree(result.getResponse().getContentAsString()).get("packages");
        for (JsonNode deliveryPackage : packages) {
            if (packageId.toString().equals(deliveryPackage.get("packageId").asText())) {
                return deliveryPackage;
            }
        }
        throw new AssertionError("Colis absent de la réponse : " + packageId);
    }

    private void insertAccount(UUID id, String username, String displayName, String role) {
        jdbcTemplate.update("""
                INSERT INTO app_account (
                    id, username, password_hash, enabled, role, display_name, phone
                ) VALUES (?, ?, 'unused', TRUE, ?, ?, '034 00 000 00')
                """, id, username, role, displayName);
    }

    private void insertCourier(UUID id, String name, boolean active) {
        jdbcTemplate.update("""
                INSERT INTO courier_profile (
                    id, name, contact, active, vehicle_type, default_departure_time,
                    departure_location_label, departure_latitude, departure_longitude
                ) VALUES (?, ?, '034 00 000 04', ?, 'MOTO', '06:00:00',
                          'Départ livreur', -18.92, 47.50)
                """, id, name, active);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor client(UUID accountId) {
        return jwt()
                .jwt(token -> token.subject("client")
                        .claim("accountId", accountId.toString())
                        .claim("role", "CLIENT"))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt()
                .jwt(token -> token.subject("admin")
                        .claim("accountId", ADMIN_ID.toString())
                        .claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
