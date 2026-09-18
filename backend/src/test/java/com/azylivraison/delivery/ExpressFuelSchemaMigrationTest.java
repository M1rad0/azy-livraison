package com.azylivraison.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ExpressFuelSchemaMigrationTest {

    @Test
    void keepsThePublishedV16ChecksumAndAppliesLaterFuelSchemaChanges() {
        try (HikariDataSource dataSource = dataSource()) {
            Flyway.configure().dataSource(dataSource)
                    .target(MigrationVersion.fromVersion("16"))
                    .load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            assertThat(jdbc.queryForObject("""
                    SELECT checksum FROM flyway_schema_history WHERE version = '16'
                    """, Integer.class)).isEqualTo(-163386769);

            Flyway.configure().dataSource(dataSource).load().migrate();

            assertThat(jdbc.queryForObject("""
                    SELECT checksum FROM flyway_schema_history WHERE version = '19'
                    """, Integer.class)).isEqualTo(1);

            assertThat(columnExists(jdbc, "express_fuel_courier_calculation",
                    "reposition_distance_meters")).isTrue();
            assertThat(columnExists(jdbc, "courier_fuel_earning",
                    "reposition_distance_meters")).isTrue();

            List<String> fuelCalculationChecks = checkClauses(jdbc, "express_fuel_calculation");
            assertThat(fuelCalculationChecks.stream()
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .anyMatch(value -> value.contains("STATUS")
                            && value.contains("MANUAL_INCOMPLETE"))).isTrue();

            List<String> fuelLineChecks = checkClauses(jdbc, "express_fuel_courier_calculation");
            assertThat(fuelLineChecks.stream()
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .anyMatch(value -> value.contains("FUEL_REIMBURSEMENT_ENABLED")
                            && value.contains("MANUAL"))).isTrue();

            verifyChangedConstraints(jdbc);
        }
    }

    private boolean columnExists(JdbcTemplate jdbc, String tableName, String columnName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE LOWER(table_name) = LOWER(?) AND LOWER(column_name) = LOWER(?)
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private List<String> checkClauses(JdbcTemplate jdbc, String tableName) {
        return jdbc.queryForList("""
                SELECT cc.check_clause
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_catalog = tc.constraint_catalog
                 AND cc.constraint_schema = tc.constraint_schema
                 AND cc.constraint_name = tc.constraint_name
                WHERE LOWER(tc.table_name) = LOWER(?)
                  AND UPPER(tc.constraint_type) = 'CHECK'
                """, String.class, tableName);
    }

    private void verifyChangedConstraints(JdbcTemplate jdbc) {
        UUID requestId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.update("""
                INSERT INTO express_fuel_calculation (
                    delivery_request_id, status, return_to_pickup
                ) VALUES (?, 'MANUAL_INCOMPLETE', TRUE)
                """, requestId);
        jdbc.update("""
                INSERT INTO express_fuel_courier_calculation (
                    delivery_request_id, courier_id, courier_name_snapshot,
                    package_ids_json, reposition_distance_meters,
                    automatic_distance_meters, final_distance_meters,
                    fuel_reimbursement_enabled, fuel_efficiency_km_per_liter,
                    fuel_price_per_liter_ariary, suggested_fuel_ariary,
                    final_fuel_ariary, override_reason, manual
                ) VALUES (?, ?, 'Livreur test', '[]', 1200, NULL, 0,
                          TRUE, NULL, 5000, 0, 0, 'Saisie manuelle', TRUE)
                """, requestId, courierId);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE express_fuel_courier_calculation
                SET reposition_distance_meters = -1
                WHERE delivery_request_id = ? AND courier_id = ?
                """, requestId, courierId)).isInstanceOf(RuntimeException.class);
    }

    private HikariDataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:express_fuel_schema_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
