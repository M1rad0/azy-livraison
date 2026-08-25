package com.azylivraison.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.finance.domain.FinanceModels.ClientDailySettlement;
import com.azylivraison.finance.repository.FinanceRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class FinancialSettlementMigrationTest {

    @Test
    void installsOneThirdAsTheDefaultCommissionAndAcceptsValidFractions() {
        try (HikariDataSource dataSource = migratedDatabase()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            Map<String, Object> defaults = jdbc.queryForMap("""
                    SELECT default_courier_commission_numerator AS numerator,
                           default_courier_commission_denominator AS denominator
                    FROM operational_settings WHERE id = 1
                    """);
            assertThat(((Number) defaults.get("numerator")).intValue()).isEqualTo(1);
            assertThat(((Number) defaults.get("denominator")).intValue()).isEqualTo(3);

            assertThat(jdbc.update("""
                    UPDATE operational_settings
                    SET default_courier_commission_numerator = 2,
                        default_courier_commission_denominator = 5
                    WHERE id = 1
                    """)).isEqualTo(1);
        }
    }

    @Test
    void rejectsInvalidGlobalCommissionFractions() {
        try (HikariDataSource dataSource = migratedDatabase()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE operational_settings
                    SET default_courier_commission_numerator = 0,
                        default_courier_commission_denominator = 3
                    WHERE id = 1
                    """))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE operational_settings
                    SET default_courier_commission_numerator = 4,
                        default_courier_commission_denominator = 3
                    WHERE id = 1
                    """))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void courierOverrideIsEitherAbsentOrACompleteValidFraction() {
        try (HikariDataSource dataSource = migratedDatabase()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            UUID courierId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO courier_profile (
                        id, name, vehicle_type, default_departure_time,
                        departure_latitude, departure_longitude
                    ) VALUES (?, 'Livreur finance', 'MOTO', '06:00:00', -18.91, 47.52)
                    """, courierId);

            assertThat(jdbc.update("""
                    UPDATE courier_profile
                    SET commission_numerator_override = 1,
                        commission_denominator_override = 4
                    WHERE id = ?
                    """, courierId)).isEqualTo(1);

            assertThat(jdbc.update("""
                    UPDATE courier_profile
                    SET commission_numerator_override = NULL,
                        commission_denominator_override = NULL
                    WHERE id = ?
                    """, courierId)).isEqualTo(1);

            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE courier_profile
                    SET commission_numerator_override = 1,
                        commission_denominator_override = NULL
                    WHERE id = ?
                    """, courierId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE courier_profile
                    SET commission_numerator_override = 5,
                        commission_denominator_override = 4
                    WHERE id = ?
                    """, courierId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void settlementMaterializationIsIdempotentWithoutAbortingTheTransaction() {
        try (HikariDataSource dataSource = migratedDatabase()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            FinanceRepository repository = new FinanceRepository(
                    jdbc, new NamedParameterJdbcTemplate(dataSource));
            UUID accountId = UUID.randomUUID();
            LocalDate serviceDate = LocalDate.of(2026, 8, 24);
            jdbc.update("""
                    INSERT INTO app_account (
                        id, username, password_hash, role, display_name
                    ) VALUES (?, ?, 'hash', 'CLIENT', 'Client finance')
                    """, accountId, "finance-" + accountId);
            jdbc.update("""
                    INSERT INTO delivery_operation_day (
                        service_date, status, closure_revision, closed_at,
                        closed_by_account_id
                    ) VALUES (?, 'CLOSED', 1, CURRENT_TIMESTAMP, ?)
                    """, serviceDate, accountId);
            jdbc.update("""
                    INSERT INTO delivery_operation_client_snapshot (
                        service_date, closure_revision, account_id, display_name
                    ) VALUES (?, 1, ?, 'Client finance')
                    """, serviceDate, accountId);

            ClientDailySettlement first = new ClientDailySettlement(
                    UUID.randomUUID(), UUID.randomUUID(), serviceDate, 1,
                    accountId, "Client finance", 12_000, 0, Instant.now());
            ClientDailySettlement duplicate = new ClientDailySettlement(
                    UUID.randomUUID(), UUID.randomUUID(), serviceDate, 1,
                    accountId, "Client finance", 12_000, 0, Instant.now());

            assertThat(repository.insertSettlement(first)).isTrue();
            assertThat(repository.insertSettlement(duplicate)).isFalse();
            assertThat(repository.findSettlement(serviceDate, 1, accountId))
                    .get().extracting(ClientDailySettlement::id)
                    .isEqualTo(first.id());
        }
    }

    private HikariDataSource migratedDatabase() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:financial_settlements_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        Flyway.configure().dataSource(dataSource).load().migrate();
        return dataSource;
    }
}
