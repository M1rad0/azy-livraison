package com.azylivraison.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalTime;
import java.util.UUID;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class DeliverySchedulingPostgreSqlMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void upgradesExistingPostgreSqlDataWithoutChangingPricesOrLosingOvernightWindows() {
        try (var dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("20")).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID owner = UUID.randomUUID(), request = UUID.randomUUID(), pkg = UUID.randomUUID();
            jdbc.update("INSERT INTO app_account (id, username, password_hash, role, display_name) VALUES (?, 'upgrade-client', 'unused', 'CLIENT', 'Client')", owner);
            jdbc.update("""
                    INSERT INTO delivery_request (id, owner_account_id, requested_date,
                        pickup_label, pickup_latitude, pickup_longitude,
                        capacity_limit_snapshot, capacity_current_snapshot, capacity_requested_snapshot, capacity_projected_snapshot)
                    VALUES (?, ?, DATE '2026-11-16', 'Retrait', -18.9, 47.5, 100, 0, 1, 1)
                    """, request, owner);
            jdbc.update("""
                    INSERT INTO delivery_package (id, delivery_request_id, sequence_number, recipient_name, faritra,
                        destination_label, destination_latitude, destination_longitude, contact,
                        amount_to_collect_ariary, mobile_money_paid, effective_weight_kg, weight_source, priority_at,
                        desired_delivery_time)
                    VALUES (?, ?, 1, 'Rakoto', FALSE, 'Lieu', -18.9, 47.5, '034',
                        12345, FALSE, 1, 'PROVIDED', CURRENT_TIMESTAMP, TIME '23:30:00')
                    """, pkg, request);
            var prices = jdbc.queryForList("SELECT id, delivery_fee_ariary FROM delivery_pricing_zone ORDER BY id");
            Flyway.configure().dataSource(dataSource).load().migrate();
            assertEquals(prices, jdbc.queryForList("SELECT id, delivery_fee_ariary FROM delivery_pricing_zone ORDER BY id"));
            assertEquals(12345L, jdbc.queryForObject("SELECT amount_to_collect_ariary FROM delivery_package WHERE id = ?", Long.class, pkg));
            assertEquals(LocalTime.of(0, 30), jdbc.queryForObject("SELECT desired_delivery_end_time FROM delivery_package WHERE id = ?", LocalTime.class, pkg));
            assertEquals("CUSTOM", jdbc.queryForObject("SELECT delivery_time_preference FROM delivery_package WHERE id = ?", String.class, pkg));
            assertEquals(500L, jdbc.queryForObject("SELECT morning_surcharge_ariary FROM delivery_pricing_policy WHERE id = 1", Long.class));
            assertEquals(500L, jdbc.queryForObject("SELECT afternoon_surcharge_ariary FROM delivery_pricing_policy WHERE id = 1", Long.class));
        }
    }
}
