package com.azylivraison.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DailyReportApiIntegrationTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 9, 2);
    private static final LocalDate NEXT_DATE = SERVICE_DATE.plusDays(1);
    private static final UUID CLIENT_ONE_ID = UUID.fromString("91000000-0000-0000-0000-000000000001");
    private static final UUID CLIENT_TWO_ID = UUID.fromString("91000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("91000000-0000-0000-0000-000000000003");
    private static final UUID COURIER_ONE_ID = UUID.fromString("92000000-0000-0000-0000-000000000001");
    private static final UUID COURIER_TWO_ID = UUID.fromString("92000000-0000-0000-0000-000000000002");
    private static final UUID PLAN_ID = UUID.fromString("93000000-0000-0000-0000-000000000001");
    private static final UUID REQUEST_ONE_ID = UUID.fromString("94000000-0000-0000-0000-000000000001");
    private static final UUID REQUEST_TWO_ID = UUID.fromString("94000000-0000-0000-0000-000000000002");
    private static final UUID SUCCESSOR_REQUEST_ID = UUID.fromString("94000000-0000-0000-0000-000000000003");
    private static final UUID DELIVERED_ID = UUID.fromString("95000000-0000-0000-0000-000000000001");
    private static final UUID CANCELLED_ID = UUID.fromString("95000000-0000-0000-0000-000000000002");
    private static final UUID RETURN_ID = UUID.fromString("95000000-0000-0000-0000-000000000003");
    private static final UUID POSTPONED_ID = UUID.fromString("95000000-0000-0000-0000-000000000004");
    private static final UUID SUCCESSOR_ID = UUID.fromString("95000000-0000-0000-0000-000000000005");
    private static final UUID PRICING_ZONE_ID = UUID.fromString("96000000-0000-0000-0000-000000000001");
    private static final Instant OUTCOME_AT = Instant.parse("2026-09-02T14:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpAccounts() {
        insertAccount(CLIENT_ONE_ID, "rado-report", "Boutique Rado actuelle", "CLIENT");
        insertAccount(CLIENT_TWO_ID, "soa-report", "Maison Soa actuelle", "CLIENT");
        insertAccount(ADMIN_ID, "admin-report", "Admin rapports", "ADMIN");
    }

    @Test
    void refusesReportsUntilTheDayIsClosed() throws Exception {
        installScenario(false);

        mockMvc.perform(get("/api/admin/delivery-days/{date}/reports/general.pdf", SERVICE_DATE)
                        .with(admin()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("DAILY_REPORT_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "Clôturez la journée avant de générer ses rapports."));
    }

    @Test
    void exportsRealGeneralPdfAndXlsxFromTheClosedOperationalDay() throws Exception {
        installScenario(true);

        MvcResult pdfResponse = mockMvc.perform(
                        get("/api/admin/delivery-days/{date}/reports/general.pdf", SERVICE_DATE)
                                .with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("rapport-general.pdf")))
                .andReturn();
        byte[] pdf = pdfResponse.getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String extracted = new PDFTextStripper().getText(document);
            assertThat(extracted)
                    .contains("Boutique Rado clôture")
                    .contains("Maison Soa clôture")
                    .contains("12 000 Ar")
                    .contains("7 000 Ar")
                    .contains("5 000 Ar")
                    .contains("Livreur prévu : Hery")
                    .contains("Livreur réel : Fara")
                    .contains("Mobile Money (informatif)")
                    .contains("Dépôt Ankorondrano");
        }

        MvcResult xlsxResponse = mockMvc.perform(
                        get("/api/admin/delivery-days/{date}/reports/general.xlsx", SERVICE_DATE)
                                .with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(AdminDailyReportController.XLSX_MEDIA_TYPE))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("rapport-general.xlsx")))
                .andReturn();
        byte[] xlsx = xlsxResponse.getResponse().getContentAsByteArray();
        assertThat(xlsx[0]).isEqualTo((byte) 'P');
        assertThat(xlsx[1]).isEqualTo((byte) 'K');
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getSheet("Synthèse")).isNotNull();
            assertThat(workbook.getSheet("Clients")).isNotNull();
            assertThat(workbook.getSheet("Livreurs")).isNotNull();
            assertThat(workbook.getSheet("Colis")).isNotNull();
            assertThat(workbook.getSheet("Retours et reports")).isNotNull();
            assertThat(summaryValue(workbook.getSheet("Synthèse"), "Total récupéré")).isEqualTo(12_000);
            assertThat(summaryValue(workbook.getSheet("Synthèse"), "Frais de livraison")).isEqualTo(7_000);
            assertThat(summaryValue(workbook.getSheet("Synthèse"), "Total à reverser")).isEqualTo(5_000);
            assertThat(sheetContains(workbook.getSheet("Retours et reports"), "03/09/2026")).isTrue();
            assertThat(sheetContains(workbook.getSheet("Retours et reports"), "Dépôt Ankorondrano")).isTrue();
            Row delivered = rowWithValue(workbook.getSheet("Colis"), 4, "Destinataire Mobile Money");
            assertThat(delivered.getCell(10).getStringCellValue()).isEqualTo("Hery");
            assertThat(delivered.getCell(11).getStringCellValue()).isEqualTo("Fara");
            Row hery = rowWithValue(workbook.getSheet("Livreurs"), 0, "Hery");
            assertThat(hery.getCell(4).getNumericCellValue()).isZero();
            assertThat(hery.getCell(7).getNumericCellValue()).isEqualTo(1);
            Row fara = rowWithValue(workbook.getSheet("Livreurs"), 0, "Fara");
            assertThat(fara.getCell(4).getNumericCellValue()).isEqualTo(1);
        }
    }

    @Test
    void clientExportsContainOnlyThatAccountsPackagesAndKeepMobileMoneyInformational() throws Exception {
        installScenario(true);

        MvcResult pdfResponse = mockMvc.perform(get(
                        "/api/admin/delivery-days/{date}/reports/clients/{accountId}.pdf",
                        SERVICE_DATE, CLIENT_ONE_ID).with(admin()))
                .andExpect(status().isOk())
                .andReturn();
        try (PDDocument document = Loader.loadPDF(pdfResponse.getResponse().getContentAsByteArray())) {
            String extracted = new PDFTextStripper().getText(document);
            assertThat(extracted)
                    .contains("Boutique Rado clôture")
                    .contains("Destinataire Mobile Money")
                    .contains("Annulation avant dispatch")
                    .contains("12 000 Ar")
                    .contains("9 000 Ar")
                    .doesNotContain("Maison Soa clôture")
                    .doesNotContain("Destinataire retour");
        }

        MvcResult xlsxResponse = mockMvc.perform(get(
                        "/api/admin/delivery-days/{date}/reports/clients/{accountId}.xlsx",
                        SERVICE_DATE, CLIENT_ONE_ID).with(admin()))
                .andExpect(status().isOk())
                .andReturn();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                xlsxResponse.getResponse().getContentAsByteArray()))) {
            Sheet packages = workbook.getSheet("Colis");
            assertThat(packages.getLastRowNum()).isEqualTo(2);
            Row delivered = rowWithValue(packages, 4, "Destinataire Mobile Money");
            assertThat(delivered.getCell(3).getStringCellValue()).isEqualTo("Boutique Rado clôture");
            assertThat(delivered.getCell(10).getStringCellValue()).isEqualTo("Hery");
            assertThat(delivered.getCell(11).getStringCellValue()).isEqualTo("Fara");
            assertThat(delivered.getCell(13).getNumericCellValue()).isEqualTo(12_000);
            assertThat(delivered.getCell(14).getNumericCellValue()).isEqualTo(3_000);
            assertThat(delivered.getCell(15).getNumericCellValue()).isEqualTo(9_000);
            assertThat(delivered.getCell(16).getStringCellValue()).isEqualTo("Oui");
            assertThat(sheetContains(packages, "Maison Soa clôture")).isFalse();
        }

        mockMvc.perform(get("/api/admin/delivery-days/{date}/reports/clients/{accountId}.pdf",
                        SERVICE_DATE, UUID.randomUUID()).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DAILY_REPORT_NOT_FOUND"));

        mockMvc.perform(get("/api/admin/delivery-days/{date}/reports/general.pdf", SERVICE_DATE)
                        .with(client()))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedClientListsAndDownloadsOnlyItsClosedDailyReports() throws Exception {
        installScenario(true);
        insertArchivedPlanRevision();

        mockMvc.perform(get("/api/client/reports").with(client(CLIENT_ONE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].serviceDate").value(SERVICE_DATE.toString()))
                .andExpect(jsonPath("$[0].revision").value(1))
                .andExpect(jsonPath("$[0].totals.packageCount").value(2))
                .andExpect(jsonPath("$[0].totals.amountCollectedAriary").value(12_000))
                .andExpect(jsonPath("$[0].totals.deliveryFeesAriary").value(3_000))
                .andExpect(jsonPath("$[0].totals.amountToRemitAriary").value(9_000));

        mockMvc.perform(get("/api/client/reports/{date}", SERVICE_DATE).with(client(CLIENT_ONE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourcePlanCount").value(1))
                .andExpect(jsonPath("$.clients.length()").value(1))
                .andExpect(jsonPath("$.clients[0].accountId").value(CLIENT_ONE_ID.toString()))
                .andExpect(jsonPath("$.couriers.length()").value(0))
                .andExpect(jsonPath("$.packages.length()").value(2))
                .andExpect(jsonPath("$.packages[0].ownerAccountId").value(CLIENT_ONE_ID.toString()));

        mockMvc.perform(get("/api/client/reports/{date}.pdf", SERVICE_DATE)
                        .with(client(CLIENT_ONE_ID)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("rapport-client.pdf")));

        mockMvc.perform(get("/api/client/reports/{date}.xlsx", SERVICE_DATE)
                        .with(client(CLIENT_ONE_ID)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(AdminDailyReportController.XLSX_MEDIA_TYPE));

        UUID accountWithoutPackages = UUID.fromString("91000000-0000-0000-0000-000000000099");
        insertAccount(accountWithoutPackages, "sans-rapport", "Sans rapport", "CLIENT");
        mockMvc.perform(get("/api/client/reports").with(client(accountWithoutPackages)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/client/reports/{date}", SERVICE_DATE)
                        .with(client(accountWithoutPackages)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/client/reports").with(admin()))
                .andExpect(status().isForbidden());
    }

    private void installScenario(boolean closed) {
        insertCourier(COURIER_ONE_ID, "Hery", "MOTO");
        insertCourier(COURIER_TWO_ID, "Fara", "BICYCLE");
        insertPlan();
        insertRequest(REQUEST_ONE_ID, CLIENT_ONE_ID, SERVICE_DATE, "Retrait Rado", 2, "PARTIALLY_DISPATCHED");
        insertRequest(REQUEST_TWO_ID, CLIENT_TWO_ID, SERVICE_DATE, "Retrait Soa", 2, "DISPATCHED");
        insertRequest(SUCCESSOR_REQUEST_ID, CLIENT_TWO_ID, NEXT_DATE, "Dépôt Ankorondrano", 1, "PENDING");

        insertPackage(DELIVERED_ID, REQUEST_ONE_ID, 1, "Destinataire Mobile Money",
                15_000, true, 3_000, "DELIVERED", 12_000L,
                COURIER_TWO_ID, "Fara", "BICYCLE", PLAN_ID, "DISPATCHED", null, null);
        insertPackage(CANCELLED_ID, REQUEST_ONE_ID, 2, "Annulation avant dispatch",
                9_000, false, 3_000, "CANCELLED", null,
                null, null, null, null, "PENDING", null, null);
        insertPackage(RETURN_ID, REQUEST_TWO_ID, 1, "Destinataire retour",
                18_000, false, 4_000, "RETURN_TO_SENDER", 0L,
                COURIER_ONE_ID, "Hery", "MOTO", PLAN_ID, "DISPATCHED",
                OUTCOME_AT.plusSeconds(3600), null);
        insertPackage(POSTPONED_ID, REQUEST_TWO_ID, 2, "Destinataire reporté",
                22_000, false, 5_000, "POSTPONED", null,
                null, null, null, PLAN_ID, "DISPATCHED", null, null);
        insertPackage(SUCCESSOR_ID, SUCCESSOR_REQUEST_ID, 1, "Destinataire reporté",
                22_000, false, 5_000, null, null,
                null, null, null, null, "PENDING", null, POSTPONED_ID);

        insertPlanCourier(COURIER_ONE_ID, "Hery", "MOTO");
        insertPlanCourier(COURIER_TWO_ID, "Fara", "BICYCLE");
        insertDispatchOutcome(DELIVERED_ID, REQUEST_ONE_ID, COURIER_ONE_ID, 1, "Destinataire Mobile Money",
                15_000, true);
        insertDispatchOutcome(RETURN_ID, REQUEST_TWO_ID, COURIER_TWO_ID, 1, "Destinataire retour",
                18_000, false);
        insertDispatchOutcome(POSTPONED_ID, REQUEST_TWO_ID, COURIER_ONE_ID, 2, "Destinataire reporté",
                22_000, false);

        if (closed) {
            jdbcTemplate.update("""
                    INSERT INTO delivery_operation_day (
                        service_date, status, admissions_closed_at, dispatch_plan_id,
                        closure_revision, closed_at, closed_by_account_id, version
                    ) VALUES (?, 'CLOSED', ?, ?, 1, ?, ?, 1)
                    """, Date.valueOf(SERVICE_DATE), Timestamp.from(OUTCOME_AT.minusSeconds(7200)), PLAN_ID,
                    Timestamp.from(OUTCOME_AT.plusSeconds(7200)), ADMIN_ID);
            insertClientSnapshot(CLIENT_ONE_ID, "Boutique Rado clôture", "034 00 100 01");
            insertClientSnapshot(CLIENT_TWO_ID, "Maison Soa clôture", "034 00 100 02");
        } else {
            jdbcTemplate.update("""
                    INSERT INTO delivery_operation_day (
                        service_date, status, admissions_closed_at, dispatch_plan_id
                    ) VALUES (?, 'DISPATCHED', ?, ?)
                    """, Date.valueOf(SERVICE_DATE), Timestamp.from(OUTCOME_AT.minusSeconds(7200)), PLAN_ID);
        }
    }

    private void insertPlan() {
        jdbcTemplate.update("""
                INSERT INTO dispatch_plan (
                    id, service_date, status, strategy, created_by_account_id,
                    idempotency_key, request_hash,
                    hub_label, hub_query, hub_latitude, hub_longitude,
                    input_snapshot_json, policy_snapshot_json,
                    selected_package_count, assigned_package_count, unassigned_package_count,
                    selected_courier_count, used_courier_count
                ) VALUES (?, ?, 'READY', 'VIA_HUB', ?, ?, ?,
                          'Siège Antsahavola', 'Antsahavola, Antananarivo', -18.90, 47.52,
                          '{}', '{}', 3, 3, 0, 2, 2)
                """, PLAN_ID, Date.valueOf(SERVICE_DATE), ADMIN_ID,
                "report-test-" + PLAN_ID, "1".repeat(64));
    }

    private void insertArchivedPlanRevision() {
        UUID archivedPlanId = UUID.fromString("93000000-0000-0000-0000-000000000099");
        jdbcTemplate.update("""
                INSERT INTO dispatch_plan (
                    id, service_date, status, strategy,
                    lifecycle_status, archived_at, archived_by_account_id, archive_reason,
                    created_by_account_id, idempotency_key, request_hash,
                    hub_label, hub_latitude, hub_longitude,
                    input_snapshot_json, policy_snapshot_json,
                    selected_package_count, selected_courier_count
                ) VALUES (?, ?, 'READY', 'VIA_HUB',
                          'SUPERSEDED', CURRENT_TIMESTAMP, ?, 'Révision remplacée',
                          ?, ?, ?, 'Ancien siège', -18.90, 47.52,
                          '{}', '{}', 1, 1)
                """, archivedPlanId, Date.valueOf(SERVICE_DATE), ADMIN_ID, ADMIN_ID,
                "report-archived-" + archivedPlanId, "9".repeat(64));
    }

    private void insertRequest(
            UUID requestId,
            UUID ownerId,
            LocalDate date,
            String pickupLabel,
            int packageCount,
            String status) {
        jdbcTemplate.update("""
                INSERT INTO delivery_request (
                    id, owner_account_id, requested_date,
                    pickup_label, pickup_query, pickup_latitude, pickup_longitude,
                    capacity_limit_snapshot, capacity_current_snapshot,
                    capacity_requested_snapshot, capacity_projected_snapshot,
                    capacity_overflow_warning, capacity_overflow_accepted, status
                ) VALUES (?, ?, ?, ?, ?, -18.91, 47.52, 100, 0, ?, ?, FALSE, FALSE, ?)
                """, requestId, ownerId, Date.valueOf(date), pickupLabel,
                pickupLabel + ", Antananarivo", packageCount, packageCount, status);
    }

    private void insertPackage(
            UUID packageId,
            UUID requestId,
            int sequence,
            String recipient,
            long amount,
            boolean mobileMoney,
            long fee,
            String outcome,
            Long actualCollected,
            UUID actualCourierId,
            String actualCourierName,
            String actualCourierVehicleType,
            UUID dispatchPlanId,
            String technicalStatus,
            Instant returnCompletedAt,
            UUID carriedForwardFrom) {
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
                    effective_weight_kg, weight_source,
                    status, priority_at, carried_forward_from_package_id
                ) VALUES (?, ?, ?, ?, FALSE, ?, ?, -18.89, 47.53,
                          '034 00 200 00', ?, ?, ?, 'Zone rapport', ?, 1000, 5000, 1,
                          1, 'PROVIDED', ?, ?, ?)
                """, packageId, requestId, sequence, recipient, recipient + " à Antananarivo",
                recipient + ", Antananarivo", amount, mobileMoney, PRICING_ZONE_ID, fee,
                technicalStatus, Timestamp.from(OUTCOME_AT.minusSeconds(sequence * 60L)), carriedForwardFrom);
        if (outcome != null) {
            jdbcTemplate.update("""
                    UPDATE delivery_package
                    SET final_outcome = ?, actual_collected_ariary = ?,
                        outcome_note = ?, outcome_recorded_at = ?, outcome_recorded_by_account_id = ?,
                        actual_delivery_courier_id = ?, actual_delivery_courier_name = ?,
                        actual_delivery_courier_contact = ?, actual_delivery_vehicle_type = ?,
                        return_completed_at = ?, return_completed_by_account_id = ?,
                        dispatch_plan_id = ?, version = 1
                    WHERE id = ?
                    """, outcome, actualCollected, "Compte rendu " + recipient,
                    Timestamp.from(OUTCOME_AT), ADMIN_ID,
                    actualCourierId, actualCourierName,
                    actualCourierId == null ? null : "034 00 300 00", actualCourierVehicleType,
                    timestamp(returnCompletedAt), returnCompletedAt == null ? null : ADMIN_ID,
                    dispatchPlanId, packageId);
        }
    }

    private void insertCourier(UUID courierId, String name, String vehicleType) {
        jdbcTemplate.update("""
                INSERT INTO courier_profile (
                    id, name, contact, active, vehicle_type, default_departure_time,
                    departure_location_label, departure_latitude, departure_longitude
                ) VALUES (?, ?, '034 00 300 00', TRUE, ?, ?, 'Départ livreur', -18.92, 47.50)
                """, courierId, name, vehicleType, Time.valueOf("06:00:00"));
    }

    private void insertPlanCourier(UUID courierId, String name, String vehicleType) {
        jdbcTemplate.update("""
                INSERT INTO dispatch_plan_courier (
                    dispatch_plan_id, courier_id, courier_name, courier_contact,
                    vehicle_type, max_weight_kg, average_speed_kmh,
                    departure_at, departure_label, departure_latitude, departure_longitude,
                    used, collection_role, distribution_role
                ) VALUES (?, ?, ?, '034 00 300 00', ?, 30, 25, ?,
                          'Départ livreur', -18.92, 47.50, TRUE, TRUE, TRUE)
                """, PLAN_ID, courierId, name, vehicleType,
                Timestamp.from(Instant.parse("2026-09-02T03:00:00Z")));
    }

    private void insertDispatchOutcome(
            UUID packageId,
            UUID requestId,
            UUID courierId,
            int order,
            String recipient,
            long amount,
            boolean mobileMoney) {
        jdbcTemplate.update("""
                INSERT INTO dispatch_package_outcome (
                    dispatch_plan_id, delivery_package_id, delivery_request_id,
                    outcome, collector_courier_id, distributor_courier_id, transferred,
                    estimated_delivery_at, delivery_order,
                    recipient_name, faritra, contact,
                    amount_to_collect_ariary, mobile_money_paid, effective_weight_kg,
                    destination_label, destination_latitude, destination_longitude
                ) VALUES (?, ?, ?, 'ASSIGNED', ?, ?, FALSE, ?, ?, ?, FALSE,
                          '034 00 200 00', ?, ?, 1, ?, -18.89, 47.53)
                """, PLAN_ID, packageId, requestId, courierId, courierId,
                Timestamp.from(OUTCOME_AT.minusSeconds(3600L - order * 300L)), order,
                recipient, amount, mobileMoney, recipient + " à Antananarivo");
    }

    private void insertClientSnapshot(UUID accountId, String displayName, String phone) {
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_client_snapshot (
                    service_date, closure_revision, account_id, display_name, phone
                ) VALUES (?, 1, ?, ?, ?)
                """, Date.valueOf(SERVICE_DATE), accountId, displayName, phone);
    }

    private void insertAccount(UUID id, String username, String displayName, String role) {
        jdbcTemplate.update("""
                INSERT INTO app_account (
                    id, username, password_hash, enabled, role, display_name, phone
                ) VALUES (?, ?, 'unused', TRUE, ?, ?, '034 00 000 00')
                """, id, username, role, displayName);
    }

    private double summaryValue(Sheet sheet, String label) {
        for (Row row : sheet) {
            if (row.getCell(0) != null && label.equals(row.getCell(0).getStringCellValue())) {
                return row.getCell(1).getNumericCellValue();
            }
        }
        throw new AssertionError("Ligne absente : " + label);
    }

    private boolean sheetContains(Sheet sheet, String expected) {
        for (Row row : sheet) {
            for (var cell : row) {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && expected.equals(cell.getStringCellValue())) return true;
            }
        }
        return false;
    }

    private Row rowWithValue(Sheet sheet, int column, String expected) {
        for (Row row : sheet) {
            if (row.getCell(column) != null && expected.equals(row.getCell(column).getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("Ligne absente : " + expected);
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt()
                .jwt(token -> token.subject("admin")
                        .claim("accountId", ADMIN_ID.toString())
                        .claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor client() {
        return client(CLIENT_ONE_ID);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor client(UUID accountId) {
        return jwt()
                .jwt(token -> token.subject("client")
                        .claim("accountId", accountId.toString())
                        .claim("role", "CLIENT"))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }
}
