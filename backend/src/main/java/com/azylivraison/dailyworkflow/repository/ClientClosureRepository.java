package com.azylivraison.dailyworkflow.repository;

import static com.azylivraison.dailyworkflow.domain.ClientClosureModels.*;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.dailyworkflow.domain.OperationDayStatus;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ClientClosureRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClientClosureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DayState> findDay(LocalDate serviceDate) {
        return queryDay(serviceDate, false);
    }

    public Optional<DayState> lockDay(LocalDate serviceDate) {
        return queryDay(serviceDate, true);
    }

    private Optional<DayState> queryDay(LocalDate serviceDate, boolean lock) {
        String sql = """
                SELECT service_date, status, closure_revision, version
                FROM delivery_operation_day
                WHERE service_date = ?
                """ + (lock ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new DayState(
                resultSet.getObject("service_date", LocalDate.class),
                OperationDayStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("closure_revision"),
                resultSet.getLong("version")), Date.valueOf(serviceDate)).stream().findFirst();
    }

    public List<ClientCandidate> clientsForDate(LocalDate serviceDate) {
        return jdbcTemplate.query("""
                SELECT account.id, account.display_name, account.phone
                FROM app_account account
                JOIN (
                    SELECT DISTINCT request.owner_account_id AS account_id
                    FROM delivery_request request
                    JOIN delivery_package package ON package.delivery_request_id = request.id
                    WHERE request.requested_date = ?
                      AND request.cancelled_before_processing_at IS NULL
                      AND package.cancelled_before_processing_at IS NULL
                    UNION
                    SELECT closure.client_account_id AS account_id
                    FROM delivery_operation_client_closure closure
                    WHERE closure.service_date = ?
                ) participating ON participating.account_id = account.id
                ORDER BY LOWER(account.display_name), account.id
                """, (resultSet, rowNumber) -> new ClientCandidate(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("display_name"),
                resultSet.getString("phone")), Date.valueOf(serviceDate), Date.valueOf(serviceDate));
    }

    public boolean clientParticipates(LocalDate serviceDate, UUID clientAccountId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_request request
                JOIN delivery_package package ON package.delivery_request_id = request.id
                WHERE request.requested_date = ?
                  AND request.owner_account_id = ?
                  AND request.cancelled_before_processing_at IS NULL
                  AND package.cancelled_before_processing_at IS NULL
                """, Integer.class, Date.valueOf(serviceDate), clientAccountId);
        return count != null && count > 0;
    }

    public ClientValidation validation(LocalDate serviceDate, UUID clientAccountId) {
        int pendingExpress = count("""
                SELECT COUNT(*)
                FROM delivery_request request
                WHERE request.requested_date = ?
                  AND request.owner_account_id = ?
                  AND request.request_kind = 'EXPRESS'
                  AND request.express_status = 'PENDING'
                  AND request.cancelled_before_processing_at IS NULL
                  AND EXISTS (
                      SELECT 1 FROM delivery_package package
                      WHERE package.delivery_request_id = request.id
                        AND package.cancelled_before_processing_at IS NULL
                  )
                """, serviceDate, clientAccountId);
        int undispatchedNormal = count("""
                SELECT COUNT(*)
                FROM delivery_package package
                JOIN delivery_request request ON request.id = package.delivery_request_id
                WHERE request.requested_date = ?
                  AND request.owner_account_id = ?
                  AND request.request_kind = 'NORMAL'
                  AND request.cancelled_before_processing_at IS NULL
                  AND package.cancelled_before_processing_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM dispatch_plan plan
                      WHERE plan.id = package.dispatch_plan_id
                        AND plan.service_date = ?
                        AND plan.status IN ('READY', 'READY_WITH_WARNINGS')
                        AND plan.lifecycle_status = 'ACTIVE'
                  )
                """, serviceDate, clientAccountId, serviceDate);

        return jdbcTemplate.query("""
                SELECT COUNT(*) AS package_count,
                       COALESCE(SUM(CASE WHEN package.final_outcome IS NULL THEN 1 ELSE 0 END), 0)
                           AS incomplete_count,
                       COALESCE(SUM(CASE
                           WHEN package.final_outcome IN ('DELIVERED', 'RETURN_TO_SENDER')
                                AND package.actual_delivery_courier_id IS NULL THEN 1 ELSE 0 END), 0)
                           AS missing_courier_count,
                       COALESCE(SUM(CASE
                           WHEN package.final_outcome = 'RETURN_TO_SENDER'
                                AND package.return_completed_at IS NULL THEN 1 ELSE 0 END), 0)
                           AS incomplete_return_count,
                       COALESCE(SUM(CASE
                           WHEN package.final_outcome = 'POSTPONED'
                                AND NOT EXISTS (
                                    SELECT 1 FROM delivery_package successor
                                    WHERE successor.carried_forward_from_package_id = package.id
                                ) THEN 1 ELSE 0 END), 0)
                           AS uncarried_postponed_count,
                       COALESCE(SUM(CASE
                           WHEN package.final_outcome IN ('DELIVERED', 'RETURN_TO_SENDER')
                               THEN COALESCE(package.actual_collected_ariary, 0)
                           ELSE 0 END), 0) AS recovered_ariary,
                       COALESCE(SUM(CASE
                           WHEN package.final_outcome IN ('DELIVERED', 'RETURN_TO_SENDER')
                               THEN COALESCE(package.final_delivery_fee_ariary,
                                             package.delivery_fee_ariary, 0)
                           ELSE 0 END), 0) AS delivery_fees_ariary
                FROM delivery_package package
                JOIN delivery_request request ON request.id = package.delivery_request_id
                WHERE request.owner_account_id = ?
                  AND request.cancelled_before_processing_at IS NULL
                  AND package.cancelled_before_processing_at IS NULL
                  AND (
                      (request.request_kind = 'NORMAL' AND EXISTS (
                          SELECT 1 FROM dispatch_plan plan
                          WHERE plan.id = package.dispatch_plan_id
                            AND plan.service_date = ?
                            AND plan.status IN ('READY', 'READY_WITH_WARNINGS')
                            AND plan.lifecycle_status = 'ACTIVE'
                      ))
                      OR (request.request_kind = 'EXPRESS'
                          AND request.requested_date = ?
                          AND request.express_status IN ('CONFIRMED', 'COMPLETED'))
                  )
                """, (resultSet, rowNumber) -> {
            long recovered = resultSet.getLong("recovered_ariary");
            long fees = resultSet.getLong("delivery_fees_ariary");
            return new ClientValidation(
                    pendingExpress, undispatchedNormal,
                    resultSet.getInt("package_count"), resultSet.getInt("incomplete_count"),
                    resultSet.getInt("missing_courier_count"), resultSet.getInt("incomplete_return_count"),
                    resultSet.getInt("uncarried_postponed_count"), recovered, fees,
                    Math.subtractExact(recovered, fees));
        }, clientAccountId, Date.valueOf(serviceDate), Date.valueOf(serviceDate)).stream()
                .findFirst().orElseThrow();
    }

    private int count(String sql, LocalDate serviceDate, UUID clientAccountId) {
        Integer count = jdbcTemplate.queryForObject(
                sql, Integer.class, Date.valueOf(serviceDate), clientAccountId);
        return count == null ? 0 : count;
    }

    private int count(String sql, LocalDate serviceDate, UUID clientAccountId, LocalDate secondDate) {
        Integer count = jdbcTemplate.queryForObject(
                sql, Integer.class, Date.valueOf(serviceDate), clientAccountId, Date.valueOf(secondDate));
        return count == null ? 0 : count;
    }

    public Optional<ClientClosure> findClosure(LocalDate serviceDate, UUID clientAccountId) {
        return queryClosure(serviceDate, clientAccountId, false);
    }

    public Optional<ClientClosure> lockClosure(LocalDate serviceDate, UUID clientAccountId) {
        return queryClosure(serviceDate, clientAccountId, true);
    }

    private Optional<ClientClosure> queryClosure(
            LocalDate serviceDate, UUID clientAccountId, boolean lock) {
        String sql = """
                SELECT closure.*,
                       EXISTS (
                           SELECT 1 FROM courier_compensation_batch batch
                           WHERE batch.service_date = closure.service_date
                             AND batch.client_account_id = closure.client_account_id
                       ) AS compensation_finalized
                FROM delivery_operation_client_closure closure
                WHERE closure.service_date = ? AND closure.client_account_id = ?
                ORDER BY closure.closure_revision DESC
                FETCH FIRST 1 ROW ONLY
                """ + (lock ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> mapClosure(resultSet),
                Date.valueOf(serviceDate), clientAccountId).stream().findFirst();
    }

    public boolean incrementDayRevision(DayState day, int nextRevision, Instant now) {
        return jdbcTemplate.update("""
                UPDATE delivery_operation_day
                SET closure_revision = ?, version = version + 1, updated_at = ?
                WHERE service_date = ? AND version = ? AND status IN ('OPEN', 'DISPATCHED')
                """, nextRevision, timestamp(now), Date.valueOf(day.serviceDate()), day.version()) == 1;
    }

    public void touchDay(DayState day, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE delivery_operation_day
                SET version = version + 1, updated_at = ?
                WHERE service_date = ? AND version = ?
                """, timestamp(now), Date.valueOf(day.serviceDate()), day.version());
        if (updated != 1) {
            throw new IllegalStateException("La journée n'a pas pu être mise à jour.");
        }
    }

    public void snapshotClient(
            LocalDate serviceDate, int revision, UUID clientAccountId, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_client_snapshot (
                    service_date, closure_revision, account_id, display_name, phone, created_at
                )
                SELECT ?, ?, account.id, account.display_name, account.phone, ?
                FROM app_account account
                WHERE account.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM delivery_operation_client_snapshot snapshot
                      WHERE snapshot.service_date = ?
                        AND snapshot.closure_revision = ?
                        AND snapshot.account_id = account.id
                  )
                """, Date.valueOf(serviceDate), revision, timestamp(now), clientAccountId,
                Date.valueOf(serviceDate), revision);
    }

    public boolean insertClosure(ClientClosure closure, Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO delivery_operation_client_closure (
                    service_date, client_account_id, status, closure_revision, report_id,
                    package_count, recovered_ariary, delivery_fees_ariary, amount_to_remit_ariary,
                    closed_at, closed_by_account_id, version, created_at, updated_at
                ) VALUES (?, ?, 'CLOSED', ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                ON CONFLICT DO NOTHING
                """, Date.valueOf(closure.serviceDate()), closure.clientAccountId(), closure.closureRevision(),
                closure.reportId(), closure.packageCount(), closure.recoveredAriary(),
                closure.deliveryFeesAriary(), closure.amountToRemitAriary(), timestamp(closure.closedAt()),
                closure.closedByAccountId(), timestamp(now), timestamp(now)) == 1;
    }

    public boolean reopenClosure(
            ClientClosure closure, UUID actorAccountId, String reason, Instant now) {
        return jdbcTemplate.update("""
                UPDATE delivery_operation_client_closure
                SET status = 'REOPENED', reopened_at = ?, reopened_by_account_id = ?,
                    reopen_reason = ?, version = version + 1, updated_at = ?
                WHERE service_date = ? AND client_account_id = ?
                  AND status = 'CLOSED' AND version = ?
                """, timestamp(now), actorAccountId, reason, timestamp(now),
                Date.valueOf(closure.serviceDate()), closure.clientAccountId(), closure.version()) == 1;
    }

    public List<ClientClosure> closedClosures(LocalDate serviceDate) {
        return jdbcTemplate.query("""
                SELECT closure.*,
                       EXISTS (
                           SELECT 1 FROM courier_compensation_batch batch
                           WHERE batch.service_date = closure.service_date
                             AND batch.client_account_id = closure.client_account_id
                       ) AS compensation_finalized
                FROM delivery_operation_client_closure closure
                WHERE closure.service_date = ? AND closure.status = 'CLOSED'
                ORDER BY closure.client_account_id
                """, (resultSet, rowNumber) -> mapClosure(resultSet), Date.valueOf(serviceDate));
    }

    public void reopenAllClosed(
            LocalDate serviceDate, UUID actorAccountId, String reason, Instant now) {
        for (ClientClosure closure : closedClosures(serviceDate)) {
            if (!reopenClosure(closure, actorAccountId, reason, now)) {
                throw new IllegalStateException("Une clôture client a été modifiée entre-temps.");
            }
            insertReopenEvent(closure, actorAccountId, reason, now);
        }
    }

    public boolean hasConfirmedPayment(ClientClosure closure) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM client_settlement_payment payment
                JOIN client_daily_settlement settlement ON settlement.id = payment.settlement_id
                WHERE settlement.service_date = ?
                  AND settlement.client_account_id = ?
                  AND settlement.closure_revision = ?
                  AND payment.status = 'CONFIRMED'
                """, Integer.class, Date.valueOf(closure.serviceDate()),
                closure.clientAccountId(), closure.closureRevision());
        return count != null && count > 0;
    }

    public boolean hasCompensationBatch(ClientClosure closure) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM courier_compensation_batch
                WHERE service_date = ? AND closure_revision = ? AND client_account_id = ?
                """, Integer.class, Date.valueOf(closure.serviceDate()),
                closure.closureRevision(), closure.clientAccountId());
        return count != null && count > 0;
    }

    public boolean allParticipatingClientsClosed(LocalDate serviceDate) {
        List<ClientCandidate> clients = clientsForDate(serviceDate);
        if (clients.isEmpty()) return false;
        return clients.stream().allMatch(client -> isExplicitlyClosed(serviceDate, client.clientAccountId()));
    }

    private boolean isExplicitlyClosed(LocalDate serviceDate, UUID clientAccountId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM delivery_operation_client_closure
                WHERE service_date = ? AND client_account_id = ? AND status = 'CLOSED'
                """, Integer.class, Date.valueOf(serviceDate), clientAccountId);
        return count != null && count == 1;
    }

    public boolean insertSettlement(ClientClosure closure, String clientDisplayName) {
        return jdbcTemplate.update("""
                INSERT INTO client_daily_settlement (
                    id, report_id, service_date, closure_revision,
                    client_account_id, client_display_name, amount_due_ariary, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), closure.reportId(), Date.valueOf(closure.serviceDate()),
                closure.closureRevision(), closure.clientAccountId(), clientDisplayName,
                closure.amountToRemitAriary(), timestamp(closure.closedAt())) == 1;
    }

    public void insertClosureEvent(ClientClosure closure) {
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day_event (
                    id, service_date, event_type, actor_account_id, payload_json, created_at
                ) VALUES (?, ?, 'CLIENT_CLOSED', ?, ?, ?)
                """, UUID.randomUUID(), Date.valueOf(closure.serviceDate()), closure.closedByAccountId(),
                "{\"clientAccountId\":\"" + closure.clientAccountId()
                        + "\",\"closureRevision\":" + closure.closureRevision() + "}",
                timestamp(closure.closedAt()));
    }

    public void insertReopenEvent(
            ClientClosure closure, UUID actorAccountId, String reason, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day_event (
                    id, service_date, event_type, actor_account_id, payload_json, created_at
                ) VALUES (?, ?, 'CLIENT_REOPENED', ?, ?, ?)
                """, UUID.randomUUID(), Date.valueOf(closure.serviceDate()), actorAccountId,
                "{\"clientAccountId\":\"" + closure.clientAccountId()
                        + "\",\"closureRevision\":" + closure.closureRevision()
                        + ",\"reason\":\"" + jsonEscape(reason) + "\"}", timestamp(now));
    }

    public boolean isClientClosed(LocalDate serviceDate, UUID clientAccountId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_operation_day operation_day
                WHERE operation_day.service_date = ?
                  AND (operation_day.status = 'CLOSED' OR EXISTS (
                      SELECT 1 FROM delivery_operation_client_closure closure
                      WHERE closure.service_date = operation_day.service_date
                        AND closure.client_account_id = ?
                        AND closure.status = 'CLOSED'
                  ))
                """, Integer.class, Date.valueOf(serviceDate), clientAccountId);
        return count != null && count > 0;
    }

    public boolean hasClosedClient(LocalDate serviceDate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM delivery_operation_client_closure
                WHERE service_date = ? AND status = 'CLOSED'
                """, Integer.class, Date.valueOf(serviceDate));
        return count != null && count > 0;
    }

    private ClientClosure mapClosure(ResultSet resultSet) throws SQLException {
        return new ClientClosure(
                resultSet.getObject("service_date", LocalDate.class),
                resultSet.getObject("client_account_id", UUID.class),
                ClientClosureStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("closure_revision"),
                resultSet.getObject("report_id", UUID.class),
                resultSet.getInt("package_count"), resultSet.getLong("recovered_ariary"),
                resultSet.getLong("delivery_fees_ariary"), resultSet.getLong("amount_to_remit_ariary"),
                resultSet.getTimestamp("closed_at").toInstant(),
                resultSet.getObject("closed_by_account_id", UUID.class),
                instant(resultSet, "reopened_at"),
                resultSet.getObject("reopened_by_account_id", UUID.class),
                resultSet.getString("reopen_reason"),
                resultSet.getLong("version"), resultSet.getBoolean("compensation_finalized"));
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
