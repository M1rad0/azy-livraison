package com.azylivraison.finance.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import com.azylivraison.finance.service.PaymentProofStorage;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs only against a disposable PostgreSQL database, never the application database. */
class CashLedgerPostgreSqlMigrationTest {
    @Test
    void upgradesPostgreSqlAndStartsAtZeroWithoutReimportingExistingReceipts() {
        String isolatedUrl = System.getProperty("cash.test.postgres.url");
        if (isolatedUrl != null) {
            // Deliberately narrow: a separately initialized local scratch cluster only.
            if (!isolatedUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/cash_ledger_test")) {
                throw new IllegalArgumentException("Only the isolated cash_ledger_test database is allowed");
            }
            verifyUpgrade(isolatedUrl, "cash_test", "");
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            verifyUpgrade(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    private void verifyUpgrade(String url, String username, String password) {
        try (var dataSource = new HikariDataSource()) {
            String schema = "cash_test_" + UUID.randomUUID().toString().replace("-", "");
            dataSource.setJdbcUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            dataSource.setUsername(username);
            dataSource.setPassword(password);
            new JdbcTemplate(dataSource).execute("CREATE SCHEMA " + schema);
            Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("22")).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID seller = UUID.randomUUID(), actor = UUID.randomUUID(), courier = UUID.randomUUID();
            UUID request = UUID.randomUUID(), pkg = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO app_account (id, username, password_hash, role, display_name)
                    VALUES (?, 'cash-upgrade-seller', 'unused', 'CLIENT', 'Vendeur historique'),
                           (?, 'cash-upgrade-admin', 'unused', 'ADMIN', 'Admin historique')
                    """, seller, actor);
            jdbc.update("""
                    INSERT INTO courier_profile (id, name, active, vehicle_type, default_departure_time,
                        departure_location_label, departure_latitude, departure_longitude)
                    VALUES (?, 'Livreur historique', TRUE, 'MOTO', TIME '06:00:00', 'Départ', -18.9, 47.5)
                    """, courier);
            jdbc.update("""
                    INSERT INTO delivery_request (id, owner_account_id, requested_date,
                        pickup_label, pickup_latitude, pickup_longitude, capacity_limit_snapshot,
                        capacity_current_snapshot, capacity_requested_snapshot, capacity_projected_snapshot)
                    VALUES (?, ?, DATE '2026-11-16', 'Retrait', -18.9, 47.5, 100, 0, 1, 1)
                    """, request, seller);
            jdbc.update("""
                    INSERT INTO delivery_package (id, delivery_request_id, sequence_number, recipient_name, faritra,
                        destination_label, destination_latitude, destination_longitude, contact,
                        amount_to_collect_ariary, mobile_money_paid, effective_weight_kg, weight_source, priority_at,
                        actual_collected_ariary, actual_delivery_courier_id, actual_delivery_courier_name,
                        actual_delivery_vehicle_type, final_outcome, outcome_recorded_at, outcome_recorded_by_account_id)
                    VALUES (?, ?, 1, 'Destinataire', FALSE, 'Repère', -18.9, 47.5, '034',
                        30000, FALSE, 1, 'PROVIDED', CURRENT_TIMESTAMP, 30000, ?, 'Livreur historique',
                        'MOTO', 'DELIVERED', CURRENT_TIMESTAMP, ?)
                    """, pkg, request, courier, actor);
            Flyway.configure().dataSource(dataSource).load().migrate();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_ledger_leg", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_package WHERE id = ?", Long.class, pkg)).isOne();
            assertThat(jdbc.queryForObject("SELECT baseline_amount_ariary FROM cash_ledger_collection_state WHERE package_id = ?", Long.class, pkg)).isEqualTo(30_000);
            var repository = new CashLedgerRepository(jdbc);
            var service = new CashLedgerService(repository, mock(PaymentProofStorage.class));
            service.syncCollection(pkg, courier, 30_000, actor);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_ledger_leg", Long.class)).isZero();
            service.syncCollection(pkg, courier, 35_000, actor);
            assertThat(repository.account("COURIER:" + courier).orElseThrow().balanceAriary()).isEqualTo(5_000);
            assertThat(repository.account("SELLER:" + seller).orElseThrow().balanceAriary()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch", Long.class)).isZero();
        }
    }
}
