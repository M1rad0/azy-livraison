package com.azylivraison.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PricingDistanceModeMigrationTest {

    @Test
    void backfillsAirDistanceWithoutChangingExistingPricingSnapshots() {
        try (HikariDataSource dataSource = legacyV14Database()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            UUID adminId = UUID.randomUUID();
            UUID clientId = UUID.randomUUID();
            UUID requestId = UUID.randomUUID();
            UUID pricedPackageId = UUID.randomUUID();
            UUID unpricedPackageId = UUID.randomUUID();
            UUID planId = UUID.randomUUID();
            UUID zoneId = UUID.randomUUID();

            jdbc.update("""
                    INSERT INTO app_account (id, username, password_hash, role, display_name)
                    VALUES (?, 'pricing-admin', 'unused', 'ADMIN', 'Admin'),
                           (?, 'pricing-client', 'unused', 'CLIENT', 'Client')
                    """, adminId, clientId);
            jdbc.update("""
                    INSERT INTO delivery_request (
                        id, owner_account_id, requested_date,
                        pickup_label, pickup_latitude, pickup_longitude,
                        capacity_limit_snapshot, capacity_current_snapshot,
                        capacity_requested_snapshot, capacity_projected_snapshot
                    ) VALUES (?, ?, '2026-09-06', 'Retrait', -18.90, 47.52, 100, 0, 2, 2)
                    """, requestId, clientId);
            insertPackage(jdbc, pricedPackageId, requestId, 1, zoneId, true);
            insertPackage(jdbc, unpricedPackageId, requestId, 2, zoneId, false);
            jdbc.update("""
                    INSERT INTO dispatch_plan (
                        id, service_date, status, strategy, created_by_account_id,
                        idempotency_key, request_hash,
                        hub_label, hub_latitude, hub_longitude,
                        input_snapshot_json, policy_snapshot_json,
                        selected_package_count, selected_courier_count
                    ) VALUES (?, '2026-09-06', 'READY', 'VIA_HUB', ?, 'pricing-distance', ?,
                              'Siege', -18.90, 47.52, '{}', '{}', 1, 1)
                    """, planId, adminId, "0".repeat(64));
            jdbc.update("""
                    INSERT INTO dispatch_package_outcome (
                        dispatch_plan_id, delivery_package_id, delivery_request_id,
                        outcome, transferred, recipient_name, faritra, contact,
                        amount_to_collect_ariary, mobile_money_paid, effective_weight_kg,
                        destination_label, destination_latitude, destination_longitude,
                        pricing_zone_id, pricing_zone_name, delivery_fee_ariary,
                        distance_from_center_meters, pricing_zone_max_distance_meters,
                        pricing_policy_version
                    ) VALUES (?, ?, ?, 'UNASSIGNED', FALSE, 'Destinataire', FALSE, '0340000000',
                              10000, FALSE, 1, 'Destination', -18.89, 47.53,
                              ?, 'Centre', 3000, 1800, 2500, 4)
                    """, planId, pricedPackageId, requestId, zoneId);

            Flyway.configure().dataSource(dataSource)
                    .target(MigrationVersion.fromVersion("15"))
                    .load().migrate();

            assertThat(jdbc.queryForList(
                    "SELECT distance_mode FROM delivery_pricing_policy ORDER BY id", String.class))
                    .containsOnly("AIR_DISTANCE");
            assertThat(jdbc.queryForObject(
                    "SELECT pricing_distance_mode FROM delivery_package WHERE id = ?",
                    String.class, pricedPackageId)).isEqualTo("AIR_DISTANCE");
            assertThat(jdbc.queryForObject(
                    "SELECT pricing_distance_mode FROM delivery_package WHERE id = ?",
                    String.class, unpricedPackageId)).isNull();
            assertThat(jdbc.queryForObject("""
                    SELECT pricing_distance_mode FROM dispatch_package_outcome
                    WHERE dispatch_plan_id = ? AND delivery_package_id = ?
                    """, String.class, planId, pricedPackageId)).isEqualTo("AIR_DISTANCE");
            assertThat(jdbc.queryForObject(
                    "SELECT delivery_fee_ariary FROM delivery_package WHERE id = ?",
                    Long.class, pricedPackageId)).isEqualTo(3000L);

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE delivery_pricing_policy SET distance_mode = 'UNKNOWN' WHERE id = 1"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private HikariDataSource legacyV14Database() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:pricing_distance_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        Flyway.configure().dataSource(dataSource)
                .target(MigrationVersion.fromVersion("14"))
                .load().migrate();
        return dataSource;
    }

    private void insertPackage(JdbcTemplate jdbc, UUID packageId, UUID requestId, int sequence,
            UUID zoneId, boolean priced) {
        if (!priced) {
            jdbc.update("""
                    INSERT INTO delivery_package (
                        id, delivery_request_id, sequence_number, recipient_name, faritra,
                        destination_label, destination_latitude, destination_longitude,
                        contact, amount_to_collect_ariary, mobile_money_paid,
                        effective_weight_kg, weight_source, priority_at
                    ) VALUES (?, ?, ?, 'Sans tarif', FALSE, 'Destination', -18.89, 47.53,
                              '0340000000', 10000, FALSE, 1, 'PROVIDED', CURRENT_TIMESTAMP)
                    """, packageId, requestId, sequence);
            return;
        }
        jdbc.update("""
                INSERT INTO delivery_package (
                    id, delivery_request_id, sequence_number, recipient_name, faritra,
                    destination_label, destination_latitude, destination_longitude,
                    contact, amount_to_collect_ariary, mobile_money_paid,
                    effective_weight_kg, weight_source,
                    pricing_zone_id, pricing_zone_name, delivery_fee_ariary,
                    distance_from_center_meters, pricing_zone_max_distance_meters,
                    pricing_policy_version, priority_at
                ) VALUES (?, ?, ?, 'Avec tarif', FALSE, 'Destination', -18.89, 47.53,
                          '0340000000', 10000, FALSE, 1, 'PROVIDED',
                          ?, 'Centre', 3000, 1800, 2500, 4, CURRENT_TIMESTAMP)
                """, packageId, requestId, sequence, zoneId);
    }
}
