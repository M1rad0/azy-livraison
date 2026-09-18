package com.azylivraison.dailyworkflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class ClientClosureMigrationTest {

    @Test
    void keepsHistoricalCompensationDayWideAndLinksNewClientCompensationToTheExactRevision() {
        try (HikariDataSource dataSource = legacyV16Database()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            UUID adminId = UUID.randomUUID();
            UUID clientId = UUID.randomUUID();
            UUID firstReportId = UUID.randomUUID();
            LocalDate serviceDate = LocalDate.of(2026, 9, 15);

            jdbc.update("""
                    INSERT INTO app_account (id, username, password_hash, role, display_name)
                    VALUES (?, ?, 'unused', 'ADMIN', 'Admin'),
                           (?, ?, 'unused', 'CLIENT', 'Client')
                    """, adminId, "closure-admin-" + adminId,
                    clientId, "closure-client-" + clientId);
            jdbc.update("""
                    INSERT INTO delivery_operation_day (
                        service_date, status, closure_revision, closed_at, closed_by_account_id
                    ) VALUES (?, 'CLOSED', 1, CURRENT_TIMESTAMP, ?)
                    """, Date.valueOf(serviceDate), adminId);
            jdbc.update("""
                    INSERT INTO delivery_operation_client_snapshot (
                        service_date, closure_revision, account_id, display_name
                    ) VALUES (?, 1, ?, 'Client')
                    """, Date.valueOf(serviceDate), clientId);
            jdbc.update("""
                    INSERT INTO client_daily_settlement (
                        id, report_id, service_date, closure_revision,
                        client_account_id, client_display_name, amount_due_ariary
                    ) VALUES (?, ?, ?, 1, ?, 'Client', 12000)
                    """, UUID.randomUUID(), firstReportId, Date.valueOf(serviceDate), clientId);
            jdbc.update("""
                    INSERT INTO courier_compensation_batch (
                        service_date, closure_revision, total_suggested_ariary,
                        total_final_ariary, finalized_by_account_id
                    ) VALUES (?, 1, 0, 0, ?)
                    """, Date.valueOf(serviceDate), adminId);

            Flyway.configure().dataSource(dataSource)
                    .target(MigrationVersion.fromVersion("17"))
                    .load().migrate();

            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM delivery_operation_client_closure
                    WHERE service_date = ? AND client_account_id = ?
                    """, Integer.class, Date.valueOf(serviceDate), clientId)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT client_account_id FROM courier_compensation_batch
                    WHERE service_date = ? AND closure_revision = 1
                    """, UUID.class, Date.valueOf(serviceDate))).isNull();

            jdbc.update("""
                    INSERT INTO delivery_operation_client_closure (
                        service_date, client_account_id, status, closure_revision, report_id,
                        package_count, recovered_ariary, delivery_fees_ariary, amount_to_remit_ariary,
                        closed_at, closed_by_account_id
                    ) VALUES (?, ?, 'CLOSED', 2, ?, 1, 15000, 3000, 12000,
                              CURRENT_TIMESTAMP, ?)
                    """, Date.valueOf(serviceDate), clientId, UUID.randomUUID(), adminId);

            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM delivery_operation_client_closure
                    WHERE service_date = ? AND client_account_id = ?
                    """, Integer.class, Date.valueOf(serviceDate), clientId)).isOne();

            insertCompensationBatch(jdbc, serviceDate, 2, adminId, clientId);
            assertThatThrownBy(() -> insertCompensationBatch(
                    jdbc, serviceDate, 3, adminId, clientId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private HikariDataSource legacyV16Database() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:client_closure_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        Flyway.configure().dataSource(dataSource)
                .target(MigrationVersion.fromVersion("16"))
                .load().migrate();
        return dataSource;
    }

    private void insertCompensationBatch(
            JdbcTemplate jdbc,
            LocalDate serviceDate,
            int closureRevision,
            UUID adminId,
            UUID clientId) {
        jdbc.update("""
                INSERT INTO courier_compensation_batch (
                    service_date, closure_revision, total_suggested_ariary,
                    total_final_ariary, finalized_by_account_id, client_account_id
                ) VALUES (?, ?, 0, 0, ?, ?)
                """, Date.valueOf(serviceDate), closureRevision, adminId, clientId);
    }
}
