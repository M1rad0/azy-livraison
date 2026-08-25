package com.azylivraison.dailyworkflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DailyWorkflowMigrationTest {

    @Test
    void preservesPackagesFromEverySuccessfulLegacyPlanOfTheDay() {
        String databaseName = "legacy_daily_workflow_" + UUID.randomUUID().toString().replace("-", "");
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        Flyway.configure()
                .dataSource(dataSource)
                .target(MigrationVersion.fromVersion("8"))
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID adminId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID firstPackageId = UUID.randomUUID();
        UUID secondPackageId = UUID.randomUUID();
        UUID firstPlanId = UUID.randomUUID();
        UUID secondPlanId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_account (
                    id, username, password_hash, enabled, role, display_name
                ) VALUES (?, 'legacy-admin', 'unused', TRUE, 'ADMIN', 'Admin')
                """, adminId);
        jdbc.update("""
                INSERT INTO app_account (
                    id, username, password_hash, enabled, role, display_name
                ) VALUES (?, 'legacy-client', 'unused', TRUE, 'CLIENT', 'Client')
                """, clientId);
        jdbc.update("""
                INSERT INTO delivery_request (
                    id, owner_account_id, requested_date,
                    pickup_label, pickup_latitude, pickup_longitude,
                    capacity_limit_snapshot, capacity_current_snapshot,
                    capacity_requested_snapshot, capacity_projected_snapshot
                ) VALUES (?, ?, '2026-08-20', 'Retrait', -18.90, 47.52, 100, 0, 2, 2)
                """, requestId, clientId);
        insertPackage(jdbc, firstPackageId, requestId, 1, "Premier");
        insertPackage(jdbc, secondPackageId, requestId, 2, "Second");
        insertPlan(jdbc, firstPlanId, adminId, "legacy-one", Instant.parse("2026-08-20T05:00:00Z"));
        insertPlan(jdbc, secondPlanId, adminId, "legacy-two", Instant.parse("2026-08-20T06:00:00Z"));
        insertOutcome(jdbc, firstPlanId, firstPackageId, requestId, "Premier", "ASSIGNED");
        insertOutcome(jdbc, secondPlanId, secondPackageId, requestId, "Second", "UNASSIGNED");

        Flyway.configure()
                .dataSource(dataSource)
                .target(MigrationVersion.fromVersion("9"))
                .load()
                .migrate();
        jdbc.update("""
                UPDATE delivery_package
                SET final_outcome = 'DELIVERED', actual_collected_ariary = 10000,
                    outcome_recorded_at = CURRENT_TIMESTAMP,
                    outcome_recorded_by_account_id = ?
                WHERE id = ?
                """, adminId, firstPackageId);
        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForObject("""
                SELECT dispatch_plan_id FROM delivery_operation_day
                WHERE service_date = '2026-08-20'
                """, UUID.class)).isEqualTo(secondPlanId);
        assertThat(jdbc.queryForObject(
                "SELECT dispatch_plan_id FROM delivery_package WHERE id = ?",
                UUID.class, firstPackageId)).isEqualTo(firstPlanId);
        assertThat(jdbc.queryForObject(
                "SELECT dispatch_plan_id FROM delivery_package WHERE id = ?",
                UUID.class, secondPackageId)).isEqualTo(secondPlanId);
        assertThat(jdbc.queryForList("""
                SELECT status FROM delivery_package
                WHERE delivery_request_id = ? ORDER BY sequence_number
                """, String.class, requestId)).containsExactly("DISPATCHED", "DISPATCHED");
        assertThat(jdbc.queryForObject(
                "SELECT unassigned_reason FROM delivery_package WHERE id = ?",
                String.class, secondPackageId)).contains("historique");
        assertThat(jdbc.queryForObject(
                "SELECT actual_delivery_courier_id IS NULL FROM delivery_package WHERE id = ?",
                Boolean.class, firstPackageId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT actual_delivery_courier_name IS NULL FROM delivery_package WHERE id = ?",
                Boolean.class, firstPackageId)).isTrue();

        UUID actualCourierId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO courier_profile (
                    id, name, vehicle_type, default_departure_time,
                    departure_latitude, departure_longitude
                ) VALUES (?, 'Livreur réel', 'MOTO', '06:00:00', -18.92, 47.50)
                """, actualCourierId);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE delivery_package
                SET actual_delivery_courier_id = ?,
                    actual_delivery_courier_name = 'Livreur réel',
                    actual_delivery_vehicle_type = 'MOTO'
                WHERE id = ?
                """, actualCourierId, secondPackageId))
                .as("un livreur réel ne peut être enregistré sans issue facturée")
                .isInstanceOf(RuntimeException.class);
        dataSource.close();
    }

    private void insertPackage(
            JdbcTemplate jdbc, UUID packageId, UUID requestId, int sequence, String recipient) {
        jdbc.update("""
                INSERT INTO delivery_package (
                    id, delivery_request_id, sequence_number,
                    recipient_name, faritra,
                    destination_label, destination_latitude, destination_longitude,
                    contact, amount_to_collect_ariary, mobile_money_paid,
                    effective_weight_kg, weight_source
                ) VALUES (?, ?, ?, ?, FALSE, 'Destination', -18.89, 47.53,
                          '034 00 000 00', 10000, FALSE, 1, 'DEFAULT_SETTING')
                """, packageId, requestId, sequence, recipient);
    }

    private void insertPlan(
            JdbcTemplate jdbc, UUID planId, UUID adminId, String key, Instant createdAt) {
        jdbc.update("""
                INSERT INTO dispatch_plan (
                    id, service_date, status, strategy, created_by_account_id,
                    idempotency_key, request_hash,
                    hub_label, hub_latitude, hub_longitude,
                    input_snapshot_json, policy_snapshot_json,
                    selected_package_count, assigned_package_count, unassigned_package_count,
                    selected_courier_count, used_courier_count, created_at
                ) VALUES (?, '2026-08-20', 'READY', 'VIA_HUB', ?, ?, ?,
                          'Siège', -18.90, 47.52, '{}', '{}', 1, 1, 0, 1, 1, ?)
                """, planId, adminId, key, "0".repeat(64), Timestamp.from(createdAt));
    }

    private void insertOutcome(
            JdbcTemplate jdbc,
            UUID planId,
            UUID packageId,
            UUID requestId,
            String recipient,
            String outcome) {
        jdbc.update("""
                INSERT INTO dispatch_package_outcome (
                    dispatch_plan_id, delivery_package_id, delivery_request_id,
                    outcome, transferred, recipient_name, faritra, contact,
                    amount_to_collect_ariary, mobile_money_paid, effective_weight_kg,
                    destination_label, destination_latitude, destination_longitude
                ) VALUES (?, ?, ?, ?, FALSE, ?, FALSE, '034 00 000 00',
                          10000, FALSE, 1, 'Destination', -18.89, 47.53)
                """, planId, packageId, requestId, outcome, recipient);
    }
}
