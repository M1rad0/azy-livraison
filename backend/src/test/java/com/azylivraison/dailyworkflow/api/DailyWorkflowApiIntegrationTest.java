package com.azylivraison.dailyworkflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 26);

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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("status", "CANCELLED")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(created.get("id").asText()))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));

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
        assertThat(outcome).isEqualTo("CANCELLED");

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

        mockMvc.perform(post("/api/admin/delivery-packages/{id}/cancel", cancelledId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isNoContent());

        installPlan(SERVICE_DATE, List.of(deliveredId, postponedId, returnId));

        MvcResult delivered = updateOutcome(deliveredId, "DELIVERED", null, 1);
        assertThat(packageNode(delivered, deliveredId).get("actualCollectedAriary").asLong())
                .isEqualTo(10_000);

        updateOutcome(postponedId, "POSTPONED", null, 1);
        updateOutcome(returnId, "RETURN_TO_SENDER", 0L, 1);

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
                .andExpect(jsonPath("$.requestedDate").value("2026-08-27"))
                .andReturn();
        UUID nextPackageId = UUID.fromString(
                objectMapper.readTree(carried.getResponse().getContentAsString()).get("packageId").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT carried_forward_from_package_id FROM delivery_package WHERE id = ?",
                UUID.class,
                nextPackageId)).isEqualTo(postponedId);

        mockMvc.perform(put("/api/admin/delivery-days/{date}/packages/{id}/outcome",
                        SERVICE_DATE, postponedId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"DELIVERED\",\"version\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Ce report a déjà créé le colis du lendemain. Son état ne peut plus être remplacé."));

        MvcResult beforeClose = mockMvc.perform(get("/api/admin/delivery-days/{date}", SERVICE_DATE)
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.total").value(4))
                .andExpect(jsonPath("$.counts.finalized").value(4))
                .andExpect(jsonPath("$.counts.cancelled").value(1))
                .andExpect(jsonPath("$.counts.postponed").value(1))
                .andExpect(jsonPath("$.counts.returnToSender").value(1))
                .andExpect(jsonPath("$.totals.recoveredAriary").value(10_000))
                .andReturn();
        JsonNode day = objectMapper.readTree(beforeClose.getResponse().getContentAsString());
        long chargedFees = day.get("totals").get("deliveryFeesAriary").asLong();
        assertThat(day.get("totals").get("amountToRemitAriary").asLong())
                .isEqualTo(10_000 - chargedFees);
        assertThat(chargedFees).isPositive();

        long dayVersion = day.get("version").asLong();
        MvcResult closed = mockMvc.perform(post("/api/admin/delivery-days/{date}/close", SERVICE_DATE)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + dayVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closureRevision").value(1))
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
    void refusesClosureUntilEveryPostponedPackageConsumesTomorrowCapacity() throws Exception {
        JsonNode created = createRequest(CLIENT_ID, SERVICE_DATE, 1);
        UUID packageId = packageId(created, 0);
        installPlan(SERVICE_DATE, List.of(packageId));
        updateOutcome(packageId, "POSTPONED", null, 1);

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
                        "Transmettez au lendemain le colis reporté avant la clôture."));
    }

    private MvcResult updateOutcome(UUID packageId, String outcome, Long actual, long version) throws Exception {
        String actualJson = actual == null ? "null" : actual.toString();
        return mockMvc.perform(put("/api/admin/delivery-days/{date}/packages/{id}/outcome",
                        SERVICE_DATE, packageId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"" + outcome + "\",\"actualCollectedAriary\":"
                                + actualJson + ",\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andReturn();
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
        for (UUID packageId : packageIds) {
            jdbcTemplate.update("""
                    UPDATE delivery_package
                    SET status = 'DISPATCHED', dispatch_plan_id = ?, version = version + 1
                    WHERE id = ?
                    """, planId, packageId);
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
