package com.azylivraison.finance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.reporting.service.DailyReportService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FinanceApiIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("f1000000-0000-0000-0000-000000000001");
    private static final UUID CLIENT_ID = UUID.fromString("f1000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_CLIENT_ID = UUID.fromString("f1000000-0000-0000-0000-000000000003");
    private static final UUID COURIER_ID = UUID.fromString("f1000000-0000-0000-0000-000000000004");
    private static final UUID SETTLEMENT_ID = UUID.fromString("f1000000-0000-0000-0000-000000000005");
    private static final UUID OTHER_SETTLEMENT_ID = UUID.fromString("f1000000-0000-0000-0000-000000000006");
    private static final UUID REPORT_ID = UUID.fromString("f1000000-0000-0000-0000-000000000007");
    private static final UUID OTHER_REPORT_ID = UUID.fromString("f1000000-0000-0000-0000-000000000008");
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 24);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The report engine is already covered by its integration suite. Finance is
     * exercised here with the immutable settlement rows that closing a day creates.
     */
    @MockitoBean
    private DailyReportService dailyReportService;

    @BeforeEach
    void setUpFinanceSnapshot() {
        when(dailyReportService.listClientReports(CLIENT_ID)).thenReturn(List.of());
        when(dailyReportService.listClientReports(OTHER_CLIENT_ID)).thenReturn(List.of());
        insertAccount(ADMIN_ID, "finance-admin", "Admin finance", "ADMIN");
        insertAccount(CLIENT_ID, "finance-client", "Boutique finance", "CLIENT");
        insertAccount(OTHER_CLIENT_ID, "finance-other", "Autre boutique", "CLIENT");
        jdbcTemplate.update("""
                INSERT INTO courier_profile (
                    id, name, contact, active, vehicle_type, default_departure_time,
                    departure_location_label, departure_latitude, departure_longitude
                ) VALUES (?, 'Hery Finance', '034 00 111 22', TRUE, 'MOTO', ?,
                          'Alasora', -18.95, 47.56)
                """, COURIER_ID, Time.valueOf("06:00:00"));
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day (
                    service_date, status, closure_revision, closed_at,
                    closed_by_account_id, version
                ) VALUES (?, 'CLOSED', 1, CURRENT_TIMESTAMP, ?, 1)
                """, Date.valueOf(SERVICE_DATE), ADMIN_ID);
        insertClientSnapshot(CLIENT_ID, "Boutique finance");
        insertClientSnapshot(OTHER_CLIENT_ID, "Autre boutique");
        insertSettlement(SETTLEMENT_ID, REPORT_ID, CLIENT_ID, "Boutique finance", 10_000);
        insertSettlement(OTHER_SETTLEMENT_ID, OTHER_REPORT_ID, OTHER_CLIENT_ID, "Autre boutique", 2_000);
    }

    @Test
    void enforcesRolesAndKeepsAClientInsideItsOwnLedger() throws Exception {
        mockMvc.perform(get("/api/admin/finance/clients/{accountId}", CLIENT_ID)
                        .with(client(CLIENT_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/client/finance").with(admin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/client/finance"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/client/finance").with(client(CLIENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientAccountId").value(CLIENT_ID.toString()))
                .andExpect(jsonPath("$.settlements.length()").value(1))
                .andExpect(jsonPath("$.settlements[0].id").value(SETTLEMENT_ID.toString()))
                .andExpect(jsonPath("$.settlements[0].reportId").value(REPORT_ID.toString()));

        mockMvc.perform(get("/api/client/finance").with(client(OTHER_CLIENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientAccountId").value(OTHER_CLIENT_ID.toString()))
                .andExpect(jsonPath("$.settlements.length()").value(1))
                .andExpect(jsonPath("$.settlements[0].id").value(OTHER_SETTLEMENT_ID.toString()))
                .andExpect(jsonPath("$.payments.length()").value(0));
    }

    @Test
    void recordsSignedPartialClientPaymentsProofOwnershipAndAuditedVoid() throws Exception {
        MvcResult partialResult = mockMvc.perform(post(
                        "/api/admin/finance/client-settlements/{settlementId}/payments", SETTLEMENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "direction": "AZY_TO_CLIENT",
                                  "amountAriary": 4000,
                                  "paymentMethod": "MVola",
                                  "transactionReference": "AZY-CLIENT-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementId").value(SETTLEMENT_ID.toString()))
                .andExpect(jsonPath("$.signedAmountAriary").value(4_000))
                .andExpect(jsonPath("$.confirmedByAccountId").value(ADMIN_ID.toString()))
                .andReturn();
        UUID partialPaymentId = responseId(partialResult);

        byte[] png = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x01, 0x02, 0x03
        };
        MockMultipartFile paymentPart = new MockMultipartFile(
                "payment", "payment.json", MediaType.APPLICATION_JSON_VALUE,
                """
                        {
                          "direction": "CLIENT_TO_AZY",
                          "amountAriary": 500,
                          "paymentMethod": "Espèces",
                          "note": "Correction signée"
                        }
                        """.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile proofPart = new MockMultipartFile(
                "proof", "preuve.png", MediaType.IMAGE_PNG_VALUE, png);
        MvcResult oppositeResult = mockMvc.perform(multipart(
                        "/api/admin/finance/client-settlements/{settlementId}/payments", SETTLEMENT_ID)
                        .file(paymentPart)
                        .file(proofPart)
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("CLIENT_TO_AZY"))
                .andExpect(jsonPath("$.signedAmountAriary").value(-500))
                .andExpect(jsonPath("$.proof.available").value(true))
                .andReturn();
        UUID oppositePaymentId = responseId(oppositeResult);

        mockMvc.perform(get("/api/client/finance").with(client(CLIENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDueAriary").value(10_000))
                .andExpect(jsonPath("$.totalConfirmedSignedPaymentsAriary").value(3_500))
                .andExpect(jsonPath("$.netBalanceAriary").value(6_500))
                .andExpect(jsonPath("$.settlements[0].status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.payments.length()").value(2));

        mockMvc.perform(get("/api/client/finance/payments/{paymentId}/proof", oppositePaymentId)
                        .with(client(CLIENT_ID)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0"));
        mockMvc.perform(get("/api/client/finance/payments/{paymentId}/proof", oppositePaymentId)
                        .with(client(OTHER_CLIENT_ID)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/admin/finance/client-payments/{paymentId}/void", oppositePaymentId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 0, "reason": "Correction annulée après vérification"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.voidedByAccountId").value(ADMIN_ID.toString()))
                .andExpect(jsonPath("$.version").value(1));

        MvcResult financeResult = mockMvc.perform(get("/api/admin/finance/clients/{accountId}", CLIENT_ID)
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalConfirmedSignedPaymentsAriary").value(4_000))
                .andExpect(jsonPath("$.netBalanceAriary").value(6_000))
                .andReturn();
        JsonNode finance = objectMapper.readTree(financeResult.getResponse().getContentAsByteArray());
        assertThat(finance.get("payments")).anySatisfy(payment -> {
            assertThat(payment.get("id").asText()).isEqualTo(oppositePaymentId.toString());
            assertThat(payment.get("status").asText()).isEqualTo("VOIDED");
            assertThat(payment.get("voidReason").asText()).contains("vérification");
        });
        assertThat(finance.get("payments")).anySatisfy(payment ->
                assertThat(payment.get("id").asText()).isEqualTo(partialPaymentId.toString()));

        // A void keeps its proof for audit and only removes the movement from balances.
        mockMvc.perform(get("/api/client/finance/payments/{paymentId}/proof", oppositePaymentId)
                        .with(client(CLIENT_ID)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(png));
    }

    @Test
    void recordsAGlobalCourierAdvanceAndRemovesItFromBalancesWhenVoided() throws Exception {
        MvcResult paymentResult = mockMvc.perform(post(
                        "/api/admin/finance/couriers/{courierId}/payments", COURIER_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amountAriary": 5000,
                                  "paymentMethod": "Orange Money",
                                  "transactionReference": "AZY-LIVREUR-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountAriary").value(5_000))
                .andExpect(jsonPath("$.confirmedByAccountId").value(ADMIN_ID.toString()))
                .andReturn();
        UUID paymentId = responseId(paymentResult);

        mockMvc.perform(get("/api/admin/finance/couriers/{courierId}", COURIER_ID).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarnedAriary").value(0))
                .andExpect(jsonPath("$.totalPaidAriary").value(5_000))
                .andExpect(jsonPath("$.remainingToPayAriary").value(0))
                .andExpect(jsonPath("$.advanceCreditAriary").value(5_000))
                .andExpect(jsonPath("$.payments.length()").value(1));

        mockMvc.perform(post("/api/admin/finance/courier-payments/{paymentId}/void", paymentId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 0, "reason": "Versement saisi sur le mauvais livreur"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.voidedByAccountId").value(ADMIN_ID.toString()))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/admin/finance/couriers/{courierId}", COURIER_ID).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPaidAriary").value(0))
                .andExpect(jsonPath("$.remainingToPayAriary").value(0))
                .andExpect(jsonPath("$.advanceCreditAriary").value(0))
                .andExpect(jsonPath("$.payments[0].status").value("VOIDED"));
    }

    private UUID responseId(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsByteArray()).get("id").asText());
    }

    private void insertAccount(UUID id, String username, String displayName, String role) {
        jdbcTemplate.update("""
                INSERT INTO app_account (
                    id, username, password_hash, enabled, role, display_name, phone
                ) VALUES (?, ?, 'unused', TRUE, ?, ?, '034 00 000 00')
                """, id, username, role, displayName);
    }

    private void insertClientSnapshot(UUID accountId, String displayName) {
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_client_snapshot (
                    service_date, closure_revision, account_id, display_name, phone
                ) VALUES (?, 1, ?, ?, '034 00 000 00')
                """, Date.valueOf(SERVICE_DATE), accountId, displayName);
    }

    private void insertSettlement(
            UUID settlementId,
            UUID reportId,
            UUID clientId,
            String displayName,
            long amountDueAriary) {
        jdbcTemplate.update("""
                INSERT INTO client_daily_settlement (
                    id, report_id, service_date, closure_revision,
                    client_account_id, client_display_name, amount_due_ariary
                ) VALUES (?, ?, ?, 1, ?, ?, ?)
                """, settlementId, reportId, Date.valueOf(SERVICE_DATE),
                clientId, displayName, amountDueAriary);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt()
                .jwt(token -> token.subject("admin")
                        .claim("accountId", ADMIN_ID.toString())
                        .claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor client(UUID accountId) {
        return jwt()
                .jwt(token -> token.subject("client")
                        .claim("accountId", accountId.toString())
                        .claim("role", "CLIENT"))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }
}
