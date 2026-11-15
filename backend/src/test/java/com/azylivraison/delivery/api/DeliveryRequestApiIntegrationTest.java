package com.azylivraison.delivery.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DeliveryRequestApiIntegrationTest {

    private static final UUID CLIENT_ONE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CLIENT_TWO_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void createAccounts() {
        insertAccount(CLIENT_ONE_ID, "client-one", "Client un", "CLIENT");
        insertAccount(CLIENT_TWO_ID, "client-two", "Client deux", "CLIENT");
        insertAccount(ADMIN_ID, "queue-admin", "Administrateur", "ADMIN");
    }

    @Test
    void createsAndReadsAnIsolatedDeliveryRequest() throws Exception {
        MvcResult creation = mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(true, false)))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.ownerAccountId").value(CLIENT_ONE_ID.toString()))
                .andExpect(jsonPath("$.ownerPhone").value("034 00 000 00"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pickup.label").value("Boutique Analakely"))
                .andExpect(jsonPath("$.packages[0].recipientName").value("Rakoto"))
                .andExpect(jsonPath("$.packages[0].faritra").value(true))
                .andExpect(jsonPath("$.packages[0].effectiveWeightKg").value(1.0))
                .andExpect(jsonPath("$.packages[0].weightSource").value("DEFAULT_SETTING"))
                .andReturn();

        String id = objectMapper.readTree(creation.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/client/delivery-requests/{id}", id).with(client(CLIENT_ONE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        mockMvc.perform(get("/api/client/delivery-requests/{id}", id).with(client(CLIENT_TWO_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_REQUEST_NOT_FOUND"));

        mockMvc.perform(get("/api/client/delivery-requests").with(client(CLIENT_TWO_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String savedPickup = jdbcTemplate.queryForObject("""
                SELECT default_pickup_label FROM client_profile WHERE account_id = ?
                """, String.class, CLIENT_ONE_ID);
        org.assertj.core.api.Assertions.assertThat(savedPickup).isEqualTo("Boutique Analakely");
    }

    @Test
    void rejectsTheWholeRequestWhenTheDailyLimitWouldBeExceeded() throws Exception {
        jdbcTemplate.update("UPDATE operational_settings SET daily_package_limit = 1 WHERE id = 1");

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DAILY_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.current").value(0))
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.projected").value(2))
                .andExpect(jsonPath("$.remaining").value(1))
                .andExpect(jsonPath("$.excess").value(1));

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DAILY_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.excess").value(1));

        Integer inserted = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_request", Integer.class);
        org.assertj.core.api.Assertions.assertThat(inserted).isZero();
    }

    @Test
    void exposesTheDatedPendingQueueToAdministrators() throws Exception {
        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, true)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/queue")
                        .param("date", "2099-08-24")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2099-08-24"))
                .andExpect(jsonPath("$.stats.pendingRequestCount").value(1))
                .andExpect(jsonPath("$.stats.pendingPackageCount").value(2))
                .andExpect(jsonPath("$.requests[0].ownerDisplayName").value("Client un"))
                .andExpect(jsonPath("$.requests[0].ownerPhone").value("034 00 000 00"))
                .andExpect(jsonPath("$.requests[0].packages.length()").value(2));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("date", "2099-08-24")
                        .param("status", "PENDING")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void keepsAPreviousDateOutOfTheNextQueueButVisibleInTheGlobalList() throws Exception {
        LocalDate requestDate = LocalDate.now(java.time.ZoneId.of("Indian/Antananarivo"));
        LocalDate nextQueueDate = requestDate.plusDays(1);
        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, false)
                                .replace("2099-08-24", requestDate.toString())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/queue")
                        .param("date", nextQueueDate.toString())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.pendingRequestCount").value(0))
                .andExpect(jsonPath("$.stats.pendingPackageCount").value(0))
                .andExpect(jsonPath("$.requests.length()").value(0));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("from", requestDate.toString())
                        .param("to", requestDate.toString())
                        .param("pendingOnly", "true")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestedDate").value(requestDate.toString()));
    }

    @Test
    void filtersTheGlobalPendingListByPeriodAndClient() throws Exception {
        MvcResult firstCreation = mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, true)))
                .andExpect(status().isCreated())
                .andReturn();
        String firstRequestId = objectMapper.readTree(firstCreation.getResponse().getContentAsString())
                .get("id").asText();
        jdbcTemplate.update("""
                UPDATE delivery_package
                SET status = 'DISPATCHED'
                WHERE delivery_request_id = ? AND sequence_number = 1
                """, UUID.fromString(firstRequestId));
        jdbcTemplate.update("UPDATE delivery_request SET status = 'PARTIALLY_DISPATCHED' WHERE id = ?",
                UUID.fromString(firstRequestId));

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_TWO_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, false)
                                .replace("2099-08-24", "2099-08-26")))
                .andExpect(status().isCreated());

        MvcResult completedCreation = mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, false)
                                .replace("2099-08-24", "2099-08-25")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID completedRequestId = UUID.fromString(objectMapper
                .readTree(completedCreation.getResponse().getContentAsString()).get("id").asText());
        jdbcTemplate.update("UPDATE delivery_package SET status = 'DISPATCHED' WHERE delivery_request_id = ?",
                completedRequestId);
        jdbcTemplate.update("UPDATE delivery_request SET status = 'DISPATCHED' WHERE id = ?", completedRequestId);

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("pendingOnly", "true")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].requestedDate").value("2099-08-24"))
                .andExpect(jsonPath("$[1].requestedDate").value("2099-08-26"));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("from", "2099-08-24")
                        .param("to", "2099-08-24")
                        .param("pendingOnly", "true")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ownerDisplayName").value("Client un"))
                .andExpect(jsonPath("$[0].status").value("PARTIALLY_DISPATCHED"))
                .andExpect(jsonPath("$[0].packages.length()").value(1))
                .andExpect(jsonPath("$[0].packages[0].recipientName").value("Soa"));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("from", "2099-08-25")
                        .param("to", "2099-08-27")
                        .param("client", "  CLIENT-TWO  ")
                        .param("pendingOnly", "true")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ownerDisplayName").value("Client deux"))
                .andExpect(jsonPath("$[0].requestedDate").value("2099-08-26"));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("client", "nom absent")
                        .param("pendingOnly", "true")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("client", "Soa")
                        .param("pendingOnly", "true")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        for (String literalQuery : new String[] {"%", "_", "\\"}) {
            mockMvc.perform(get("/api/admin/delivery-requests")
                            .param("client", literalQuery)
                            .param("pendingOnly", "true")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Test
    void rejectsAmbiguousOrReversedAdministrationDateFilters() throws Exception {
        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("date", "2099-08-24")
                        .param("from", "2099-08-23")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_REQUEST_FILTER_INVALID"))
                .andExpect(jsonPath("$.message").value("Le filtre date ne peut pas être combiné avec une période."));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("from", "2099-08-27")
                        .param("to", "2099-08-25")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_REQUEST_FILTER_INVALID"))
                .andExpect(jsonPath("$.message").value(
                        "La date de début doit précéder ou être égale à la date de fin."));
    }

    @Test
    void keepsTheGlobalPendingListRestrictedToAdministrators() throws Exception {
        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("pendingOnly", "true")
                        .with(client(CLIENT_ONE_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void validatesEveryRequiredBusinessField() throws Exception {
        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedDate": "2099-08-24",
                                  "pickup": {
                                    "label": "Boutique", "latitude": -18.91, "longitude": 47.52
                                  },
                                  "packages": [{
                                    "recipientName": "",
                                    "destination": null,
                                    "contact": "",
                                    "amountToCollectAriary": null
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_REQUEST_INVALID"))
                .andExpect(jsonPath("$.violations.length()").value(6));
    }

    @Test
    void persistsLongCustomWindowsAndMorningPreference() throws Exception {
        mockMvc.perform(post("/api/client/delivery-requests").with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, false).replace("\"mobileMoneyPaid\": false",
                                "\"mobileMoneyPaid\": false, \"desiredDeliveryTime\":\"08:00\","
                                        + "\"desiredDeliveryEndTime\":\"11:30\",\"deliveryTimePreference\":\"CUSTOM\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packages[0].desiredDeliveryTime").value("08:00:00"))
                .andExpect(jsonPath("$.packages[0].desiredDeliveryEndTime").value("11:30:00"))
                .andExpect(jsonPath("$.packages[0].deliveryTimePreference").value("CUSTOM"))
                .andExpect(jsonPath("$.packages[0].canEdit").value(true));
        mockMvc.perform(post("/api/client/delivery-requests").with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, false).replace("\"mobileMoneyPaid\": false",
                                "\"mobileMoneyPaid\": false,\"deliveryTimePreference\":\"MORNING\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packages[0].desiredDeliveryTime").value("00:00:00"))
                .andExpect(jsonPath("$.packages[0].desiredDeliveryEndTime").value("12:00:00"))
                .andExpect(jsonPath("$.packages[0].deliveryTimePreference").value("MORNING"));
    }

    @Test
    void ownerCanEditAllPackageInputsWithOptimisticVersionAndAdminOverridePreserved() throws Exception {
        var created = createSinglePackage(false);
        String requestId = created.get("id").asText();
        String packageId = created.get("packages").get(0).get("id").asText();
        jdbcTemplate.update("""
                UPDATE delivery_package SET final_delivery_fee_ariary = 1234,
                    delivery_fee_override_reason = 'Accord commercial', delivery_fee_overridden_at = CURRENT_TIMESTAMP,
                    delivery_fee_overridden_by_account_id = ? WHERE id = ?
                """, ADMIN_ID, UUID.fromString(packageId));
        mockMvc.perform(put("/api/client/delivery-packages/{id}", packageId).with(client(CLIENT_TWO_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(updateJson(0)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/client/delivery-packages/{id}", packageId).with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(updateJson(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId))
                .andExpect(jsonPath("$.packages[0].recipientName").value("Nouveau destinataire"))
                .andExpect(jsonPath("$.packages[0].faritra").value(false))
                .andExpect(jsonPath("$.packages[0].destination.label").value("Nouvelle adresse"))
                .andExpect(jsonPath("$.packages[0].contact").value("034 11 222 33"))
                .andExpect(jsonPath("$.packages[0].amountToCollectAriary").value(98000))
                .andExpect(jsonPath("$.packages[0].mobileMoneyPaid").value(true))
                .andExpect(jsonPath("$.packages[0].effectiveWeightKg").value(3.5))
                .andExpect(jsonPath("$.packages[0].desiredDeliveryEndTime").value("18:00:00"))
                .andExpect(jsonPath("$.packages[0].finalDeliveryFeeAriary").value(1234))
                .andExpect(jsonPath("$.packages[0].deliveryFeeOverrideReason").value("Accord commercial"))
                .andExpect(jsonPath("$.packages[0].version").value(1));
        mockMvc.perform(put("/api/admin/delivery-packages/{id}", packageId).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(updateJson(0)))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/admin/delivery-packages/{id}", packageId).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(updateJson(1)))
                .andExpect(status().isOk());
    }

    @Test
    void dailyValidationFreezesEvenUnassignedPendingPackages() throws Exception {
        var created = createSinglePackage(false);
        String packageId = created.get("packages").get(0).get("id").asText();
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO dispatch_plan(id,service_date,status,created_by_account_id,idempotency_key,request_hash,
                    hub_label,hub_latitude,hub_longitude,input_snapshot_json,policy_snapshot_json,
                    selected_package_count,selected_courier_count)
                VALUES (?,'2099-08-24','READY',?,'validated-day',?,'Hub',-18.9,47.5,'{}','{}',1,1)
                """, planId, ADMIN_ID, "0".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day(service_date,status,dispatch_plan_id)
                VALUES ('2099-08-24','DISPATCHED',?)
                """, planId);
        mockMvc.perform(get("/api/client/delivery-requests/{id}", created.get("id").asText()).with(client(CLIENT_ONE_ID)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.packages[0].canEdit").value(false));
        mockMvc.perform(put("/api/client/delivery-packages/{id}", packageId).with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(updateJson(0)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/admin/delivery-packages/{id}/reschedule", packageId).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"requestedDate\":\"2099-08-25\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void reschedulingMovesOnlyOnePackageAndPreservesSourceDatePickupAndPrice() throws Exception {
        var created = createSinglePackage(true);
        String requestId = created.get("id").asText();
        String packageId = created.get("packages").get(0).get("id").asText();
        long price = created.get("packages").get(0).get("finalDeliveryFeeAriary").asLong();
        MvcResult moved = mockMvc.perform(post("/api/client/delivery-packages/{id}/reschedule", packageId)
                        .with(client(CLIENT_ONE_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"requestedDate\":\"2099-08-25\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedDate").value("2099-08-25"))
                .andExpect(jsonPath("$.pickup.label").value("Boutique Analakely"))
                .andExpect(jsonPath("$.packages.length()").value(1))
                .andExpect(jsonPath("$.packages[0].carriedForwardFromPackageId").value(packageId))
                .andExpect(jsonPath("$.packages[0].finalDeliveryFeeAriary").value(price))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(moved.getResponse().getContentAsString()).get("id").asText())
                .isNotEqualTo(requestId);
        mockMvc.perform(get("/api/client/delivery-requests/{id}", requestId).with(client(CLIENT_ONE_ID)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestedDate").value("2099-08-24"))
                .andExpect(jsonPath("$.packages.length()").value(1))
                .andExpect(jsonPath("$.packages[0].recipientName").value("Soa"));
        Integer sourceAllocated = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM delivery_package p JOIN delivery_request r ON r.id = p.delivery_request_id
                WHERE r.requested_date = '2099-08-24' AND p.cancelled_before_processing_at IS NULL
                """, Integer.class);
        org.assertj.core.api.Assertions.assertThat(sourceAllocated).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT requested_date FROM delivery_request WHERE id = ?", LocalDate.class, UUID.fromString(requestId)))
                .isEqualTo(LocalDate.of(2099, 8, 24));
    }

    @Test
    void reschedulingRejectsFullAndClosedTargetsWithoutRetiringSource() throws Exception {
        jdbcTemplate.update("UPDATE operational_settings SET daily_package_limit = 1 WHERE id = 1");
        var created = createSinglePackage(false);
        String packageId = created.get("packages").get(0).get("id").asText();
        mockMvc.perform(post("/api/client/delivery-requests").with(client(CLIENT_TWO_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, false).replace("2099-08-24", "2099-08-25")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/client/delivery-packages/{id}/reschedule", packageId).with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"requestedDate\":\"2099-08-25\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DAILY_CAPACITY_EXCEEDED"));
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day(service_date,status,closed_at,closed_by_account_id)
                VALUES ('2099-08-26','CLOSED',CURRENT_TIMESTAMP,?)
                """, ADMIN_ID);
        mockMvc.perform(post("/api/client/delivery-packages/{id}/reschedule", packageId).with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"requestedDate\":\"2099-08-26\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/client/delivery-requests/{id}", created.get("id").asText()).with(client(CLIENT_ONE_ID)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.packages[0].version").value(0));
    }

    @Test
    void expressIsEditableAndReprogrammableOnlyBeforeConfirmation() throws Exception {
        jdbcTemplate.update("INSERT INTO client_profile(account_id,express_delivery_enabled) VALUES (?,TRUE)", CLIENT_ONE_ID);
        String body = requestJson(false, false).replace("\"requestedDate\": \"2099-08-24\"",
                "\"requestKind\":\"EXPRESS\"");
        var first = objectMapper.readTree(mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String packageId = first.get("packages").get(0).get("id").asText();
        mockMvc.perform(put("/api/client/delivery-packages/{id}", packageId).with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(updateJson(0)
                                .replace("\"desiredDeliveryTime\":\"13:00\",\"desiredDeliveryEndTime\":\"18:00\",\"deliveryTimePreference\":\"CUSTOM\"",
                                        "\"deliveryTimePreference\":\"NONE\"")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/client/delivery-packages/{id}/reschedule", packageId).with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"requestedDate\":\"2099-08-25\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestKind").value("NORMAL"));
        var second = objectMapper.readTree(mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        jdbcTemplate.update("UPDATE delivery_request SET express_status='CONFIRMED' WHERE id = ?",
                UUID.fromString(second.get("id").asText()));
        mockMvc.perform(post("/api/admin/delivery-packages/{id}/reschedule",
                        second.get("packages").get(0).get("id").asText()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"requestedDate\":\"2099-08-25\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void editsAndMovesPackagesWhileManualDraftsArePreparedAndSynchronizesBothDrafts() throws Exception {
        var source = createSinglePackage(true);
        String packageId = source.get("packages").get(0).get("id").asText();
        mockMvc.perform(post("/api/client/delivery-requests").with(client(CLIENT_TWO_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, false).replace("2099-08-24", "2099-08-25")))
                .andExpect(status().isCreated());
        UUID courier = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO courier_profile(id,name,active,vehicle_type,default_departure_time,
                    departure_location_label,departure_latitude,departure_longitude)
                VALUES (?,'Livreur test',TRUE,'MOTO','06:00','Depart',-18.9,47.5)
                """, courier);
        createManualDraft("2099-08-24", courier, "source-draft");
        createManualDraft("2099-08-25", courier, "target-draft");
        mockMvc.perform(put("/api/client/delivery-packages/{id}", packageId).with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(updateJson(0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.packages[0].canEdit").value(true));
        mockMvc.perform(get("/api/admin/manual-dispatch-drafts/active").param("date", "2099-08-24").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.packages[?(@.packageId == '" + packageId + "')].recipientName")
                        .value("Nouveau destinataire"));
        mockMvc.perform(post("/api/client/delivery-packages/{id}/reschedule", packageId).with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"requestedDate\":\"2099-08-25\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/manual-dispatch-drafts/active").param("date", "2099-08-24").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.packages.length()").value(1));
        mockMvc.perform(get("/api/admin/manual-dispatch-drafts/active").param("date", "2099-08-25").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.packages.length()").value(2));
    }

    private void createManualDraft(String date, UUID courier, String key) throws Exception {
        mockMvc.perform(post("/api/admin/manual-dispatch-drafts").with(admin())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceDate":"%s","courierIds":["%s"],
                                 "hubOverride":{"label":"Hub test","latitude":-18.9,"longitude":47.5}}
                                """.formatted(date, courier)))
                .andExpect(status().isOk());
    }

    private tools.jackson.databind.JsonNode createSinglePackage(boolean twoPackages) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/client/delivery-requests").with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(requestJson(false, twoPackages)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String updateJson(long version) {
        return """
                {"expectedVersion":%d,"recipientName":"Nouveau destinataire","faritra":false,
                 "destination":{"label":"Nouvelle adresse","query":"Analakely","latitude":-18.9096,"longitude":47.5236},
                 "contact":"034 11 222 33","amountToCollectAriary":98000,"mobileMoneyPaid":true,"weightKg":3.5,
                 "desiredDeliveryTime":"13:00","desiredDeliveryEndTime":"18:00","deliveryTimePreference":"CUSTOM"}
                """.formatted(version);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor client(UUID accountId) {
        return jwt()
                .jwt(token -> token.subject("client").claim("accountId", accountId.toString()).claim("role", "CLIENT"))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt()
                .jwt(token -> token.subject("admin").claim("accountId", ADMIN_ID.toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private void insertAccount(UUID id, String username, String displayName, String role) {
        jdbcTemplate.update("""
                INSERT INTO app_account (
                    id, username, password_hash, enabled, role, display_name, phone
                ) VALUES (?, ?, ?, TRUE, ?, ?, ?)
                """, id, username, "unused-password-hash", role, displayName, "034 00 000 00");
    }

    private String requestJson(boolean savePickup, boolean twoPackages) {
        String secondPackage = twoPackages ? """
                ,{
                  "recipientName": "Soa",
                  "faritra": false,
                  "destination": {
                    "label": "Ankorondrano",
                    "query": "Ankorondrano, Antananarivo",
                    "latitude": -18.8792,
                    "longitude": 47.5215
                  },
                  "contact": "034 00 000 01",
                  "amountToCollectAriary": 0,
                  "mobileMoneyPaid": true,
                  "weightKg": 2.5
                }
                """ : "";
        return """
                {
                  "requestedDate": "2099-08-24",
                  "pickup": {
                    "label": "Boutique Analakely",
                    "query": "Analakely, Antananarivo",
                    "latitude": -18.9089,
                    "longitude": 47.5212
                  },
                  "saveAsDefaultPickup": %s,
                  "packages": [{
                    "recipientName": "Rakoto",
                    "faritra": true,
                    "destination": {
                      "label": "Ampasampito",
                      "query": "Ampasampito, Antananarivo",
                      "latitude": -18.8957,
                      "longitude": 47.5458
                    },
                    "contact": "032 00 000 00",
                    "amountToCollectAriary": 45000,
                    "mobileMoneyPaid": false
                  }%s]
                }
                """.formatted(savePickup, secondPackage);
    }
}
