package com.azylivraison.delivery.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                        .param("date", "2026-08-24")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-08-24"))
                .andExpect(jsonPath("$.stats.pendingRequestCount").value(1))
                .andExpect(jsonPath("$.stats.pendingPackageCount").value(2))
                .andExpect(jsonPath("$.requests[0].ownerDisplayName").value("Client un"))
                .andExpect(jsonPath("$.requests[0].ownerPhone").value("034 00 000 00"))
                .andExpect(jsonPath("$.requests[0].packages.length()").value(2));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("date", "2026-08-24")
                        .param("status", "PENDING")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void keepsAPreviousDateOutOfTheNextQueueButVisibleInTheGlobalList() throws Exception {
        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, false)
                                .replace("2026-08-24", "2026-08-23")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/queue")
                        .param("date", "2026-08-24")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.pendingRequestCount").value(0))
                .andExpect(jsonPath("$.stats.pendingPackageCount").value(0))
                .andExpect(jsonPath("$.requests.length()").value(0));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("from", "2026-08-23")
                        .param("to", "2026-08-23")
                        .param("pendingOnly", "true")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestedDate").value("2026-08-23"));
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
                                .replace("2026-08-24", "2026-08-26")))
                .andExpect(status().isCreated());

        MvcResult completedCreation = mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client(CLIENT_ONE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(false, false)
                                .replace("2026-08-24", "2026-08-25")))
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
                .andExpect(jsonPath("$[0].requestedDate").value("2026-08-24"))
                .andExpect(jsonPath("$[1].requestedDate").value("2026-08-26"));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("from", "2026-08-24")
                        .param("to", "2026-08-24")
                        .param("pendingOnly", "true")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ownerDisplayName").value("Client un"))
                .andExpect(jsonPath("$[0].status").value("PARTIALLY_DISPATCHED"))
                .andExpect(jsonPath("$[0].packages.length()").value(1))
                .andExpect(jsonPath("$[0].packages[0].recipientName").value("Soa"));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("from", "2026-08-25")
                        .param("to", "2026-08-27")
                        .param("client", "  CLIENT-TWO  ")
                        .param("pendingOnly", "true")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ownerDisplayName").value("Client deux"))
                .andExpect(jsonPath("$[0].requestedDate").value("2026-08-26"));

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
                        .param("date", "2026-08-24")
                        .param("from", "2026-08-23")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_REQUEST_FILTER_INVALID"))
                .andExpect(jsonPath("$.message").value("Le filtre date ne peut pas être combiné avec une période."));

        mockMvc.perform(get("/api/admin/delivery-requests")
                        .param("from", "2026-08-27")
                        .param("to", "2026-08-25")
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
                                  "requestedDate": "2026-08-24",
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
                  "requestedDate": "2026-08-24",
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
