package com.azylivraison.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PricingPeriodsAndKeywordRulesMigrationTest {

    @Test
    void backfillsEveryPolicyWithoutChangingZonePricesAndScopesOrderedRules() {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:h2:mem:pricing_periods_" + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("20")).load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            UUID client = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO app_account (id, username, password_hash, role, display_name)
                    VALUES (?, 'migration-pricing-client', 'unused', 'CLIENT', 'Client')
                    """, client);
            jdbc.update("""
                    INSERT INTO delivery_pricing_policy
                        (id, center_label, center_latitude, center_longitude, client_account_id, request_kind, active)
                    VALUES (3, 'Centre client', 0, 0, ?, 'NORMAL', TRUE),
                           (4, 'Centre client express', 0, 0, ?, 'EXPRESS', FALSE)
                    """, client, client);
            var originalZones = jdbc.queryForList("""
                    SELECT id, delivery_fee_ariary FROM delivery_pricing_zone ORDER BY id
                    """);

            Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("21")).load().migrate();

            assertThat(jdbc.queryForList("SELECT morning_surcharge_ariary FROM delivery_pricing_policy", Long.class))
                    .containsExactly(500L, 500L, 500L, 500L);
            assertThat(jdbc.queryForList("SELECT afternoon_surcharge_ariary FROM delivery_pricing_policy", Long.class))
                    .containsExactly(500L, 500L, 500L, 500L);
            assertThat(jdbc.queryForList("SELECT id, delivery_fee_ariary FROM delivery_pricing_zone ORDER BY id"))
                    .isEqualTo(originalZones);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_pricing_keyword_rule", Integer.class)).isZero();

            insertRule(jdbc, (short) 1, 1, "école", 1_000);
            insertRule(jdbc, (short) 2, 1, "école", 2_000);
            insertRule(jdbc, (short) 3, 1, "école", 3_000);
            assertThat(jdbc.queryForList("""
                    SELECT delivery_fee_ariary FROM delivery_pricing_keyword_rule ORDER BY pricing_policy_id
                    """, Long.class)).containsExactly(1_000L, 2_000L, 3_000L);
            assertThatThrownBy(() -> insertRule(jdbc, (short) 1, 1, "Autre", 4_000))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> insertRule(jdbc, (short) 1, 2, "   ", 4_000))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE delivery_pricing_policy SET morning_surcharge_ariary = -1 WHERE id = 1
                    """)).isInstanceOf(RuntimeException.class);
        }
    }

    private void insertRule(JdbcTemplate jdbc, short policy, int order, String keyword, long amount) {
        jdbc.update("""
                INSERT INTO delivery_pricing_keyword_rule
                    (id, pricing_policy_id, rule_order, keyword, delivery_fee_ariary)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), policy, order, keyword, amount);
    }
}
