package com.azylivraison.finance.cash.briefing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CashPayoutNumberMigrationTest {
    @Test void upgradingExistingProposalsKeepsTheirProfileBasedBehaviorAndConfirmedSnapshots() {
        try (var database = new HikariDataSource()) {
            database.setJdbcUrl("jdbc:h2:mem:payout_number_upgrade_" + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
            database.setUsername("sa"); database.setPassword("");
            Flyway.configure().dataSource(database).target(MigrationVersion.fromVersion("25")).load().migrate();
            var jdbc = new JdbcTemplate(database);
            UUID actor = UUID.randomUUID(), seller = UUID.randomUUID(), courier = UUID.randomUUID();
            UUID plan = UUID.randomUUID(), batch = UUID.randomUUID(), pending = UUID.randomUUID(), confirmed = UUID.randomUUID();
            UUID movement = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO app_account (id, username, password_hash, enabled, role, display_name)
                    VALUES (?, 'payout-upgrade-admin', 'unused', TRUE, 'ADMIN', 'Admin'),
                           (?, 'payout-upgrade-seller', 'unused', TRUE, 'CLIENT', 'Vendeur')
                    """, actor, seller);
            jdbc.update("""
                    INSERT INTO courier_profile (id, name, vehicle_type, default_departure_time,
                        departure_location_label, departure_latitude, departure_longitude)
                    VALUES (?, 'Livreur', 'MOTO', '06:00:00', 'Départ', -18.9, 47.5)
                    """, courier);
            jdbc.update("""
                    INSERT INTO dispatch_plan (id, service_date, status, strategy, mode, created_by_account_id,
                        idempotency_key, request_hash, hub_label, hub_latitude, hub_longitude,
                        input_snapshot_json, policy_snapshot_json, selected_package_count, selected_courier_count)
                    VALUES (?, DATE '2099-01-01', 'READY', 'VIA_HUB', 'MANUAL', ?, 'old-plan', ?,
                        'Ralliement', -18.9, 47.5, '{}', '{}', 1, 1)
                    """, plan, actor, "0".repeat(64));
            jdbc.update("""
                    INSERT INTO cash_briefing_batch (id, plan_id, service_date, ledger_revision, status,
                        actor_account_id, idempotency_key, expires_at, planning_mode)
                    VALUES (?, ?, DATE '2099-01-01', 0, 'ACTIVE', ?, 'old-payouts',
                        TIMESTAMP WITH TIME ZONE '2099-01-02 00:00:00+03:00', 'MANUAL')
                    """, batch, plan, actor);
            jdbc.update("""
                    INSERT INTO cash_briefing_transfer (id, batch_id, seller_account_id, seller_name,
                        seller_payout_number, courier_account_id, courier_name, amount_ariary, status, movement_id)
                    VALUES (?, ?, ?, 'Vendeur', '034 11 111 11', ?, 'Livreur', 2000, 'PENDING', NULL),
                           (?, ?, ?, 'Vendeur', '034 22 222 22', ?, 'Livreur', 3000, 'CONFIRMED', ?)
                    """, pending, batch, seller, courier, confirmed, batch, seller, courier, movement);

            Flyway.configure().dataSource(database).target(MigrationVersion.fromVersion("26")).load().migrate();
            assertThat(jdbc.queryForList("SELECT seller_payout_number_override FROM cash_briefing_transfer", Boolean.class))
                    .containsExactlyInAnyOrder(false, false);
            // A custom V26 destination remains custom; V27 must not infer its unknown holder.
            jdbc.update("UPDATE cash_briefing_transfer SET seller_payout_number_override = TRUE WHERE id = ?", confirmed);
            Flyway.configure().dataSource(database).load().migrate();
            Flyway.configure().dataSource(database).load().validate();

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_transfer", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT seller_payout_number_override FROM cash_briefing_transfer", Boolean.class))
                    .containsExactlyInAnyOrder(false, true);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM cash_briefing_transfer WHERE seller_payout_destination_id IS NULL
                        AND seller_payout_account_holder IS NULL AND seller_payout_operator IS NULL
                    """, Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT seller_payout_number FROM cash_briefing_transfer WHERE id = ?",
                    String.class, pending)).isEqualTo("034 11 111 11");
            assertThat(jdbc.queryForObject("SELECT seller_payout_number FROM cash_briefing_transfer WHERE id = ?",
                    String.class, confirmed)).isEqualTo("034 22 222 22");
            assertThat(jdbc.queryForObject("SELECT status FROM cash_briefing_transfer WHERE id = ?",
                    String.class, confirmed)).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("SELECT movement_id FROM cash_briefing_transfer WHERE id = ?",
                    UUID.class, confirmed)).isEqualTo(movement);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_ledger_leg", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT workflow_version FROM cash_briefing_batch WHERE id = ?", Integer.class, batch)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_courier_transfer", Long.class)).isZero();
        }
    }
}
