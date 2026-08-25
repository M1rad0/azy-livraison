package com.azylivraison.finance.repository;

import static com.azylivraison.finance.domain.FinanceModels.*;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryPackageOutcome;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinanceRepository {

    private static final String CLIENT_PAYMENT_COLUMNS = """
            payment.id, payment.settlement_id, settlement.client_account_id,
            settlement.service_date, payment.direction, payment.amount_ariary,
            payment.payment_method, payment.destination_operator,
            payment.destination_account_holder, payment.destination_number,
            payment.transaction_reference, payment.note,
            payment.proof_storage_key, payment.proof_original_filename,
            payment.proof_content_type, payment.proof_size_bytes, payment.proof_sha256,
            payment.status, payment.confirmed_at, payment.confirmed_by_account_id,
            payment.voided_at, payment.voided_by_account_id, payment.void_reason,
            payment.version
            """;

    private static final String COURIER_PAYMENT_COLUMNS = """
            payment.id, payment.courier_id, payment.amount_ariary,
            payment.payment_method, payment.destination_operator,
            payment.destination_account_holder, payment.destination_number,
            payment.transaction_reference, payment.note,
            payment.proof_storage_key, payment.proof_original_filename,
            payment.proof_content_type, payment.proof_size_bytes, payment.proof_sha256,
            payment.status, payment.confirmed_at, payment.confirmed_by_account_id,
            payment.voided_at, payment.voided_by_account_id, payment.void_reason,
            payment.version
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public FinanceRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    public Optional<ClosedDay> findClosedDay(LocalDate serviceDate) {
        return jdbcTemplate.query("""
                SELECT service_date, status, closure_revision
                FROM delivery_operation_day
                WHERE service_date = ? AND status = 'CLOSED'
                """, (resultSet, rowNumber) -> mapClosedDay(resultSet), Date.valueOf(serviceDate))
                .stream().findFirst();
    }

    public Optional<ClosedDay> lockDay(LocalDate serviceDate) {
        return jdbcTemplate.query("""
                SELECT service_date, status, closure_revision
                FROM delivery_operation_day
                WHERE service_date = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> mapClosedDay(resultSet), Date.valueOf(serviceDate))
                .stream().findFirst();
    }

    public Optional<ClientDailySettlement> findSettlement(
            LocalDate serviceDate,
            int closureRevision,
            UUID clientAccountId) {
        return jdbcTemplate.query("""
                SELECT settlement.*,
                       COALESCE(SUM(CASE
                           WHEN payment.status = 'CONFIRMED' AND payment.direction = 'AZY_TO_CLIENT'
                               THEN payment.amount_ariary
                           WHEN payment.status = 'CONFIRMED' AND payment.direction = 'CLIENT_TO_AZY'
                               THEN -payment.amount_ariary
                           ELSE 0 END), 0) AS confirmed_signed_payments_ariary
                FROM client_daily_settlement settlement
                LEFT JOIN client_settlement_payment payment
                       ON payment.settlement_id = settlement.id
                WHERE settlement.service_date = ?
                  AND settlement.closure_revision = ?
                  AND settlement.client_account_id = ?
                GROUP BY settlement.id
                """, (resultSet, rowNumber) -> mapSettlement(resultSet),
                Date.valueOf(serviceDate), closureRevision, clientAccountId).stream().findFirst();
    }

    public Optional<ClientDailySettlement> lockSettlement(UUID settlementId) {
        return jdbcTemplate.query("""
                SELECT settlement.*,
                       COALESCE((SELECT SUM(CASE
                           WHEN payment.status = 'CONFIRMED' AND payment.direction = 'AZY_TO_CLIENT'
                               THEN payment.amount_ariary
                           WHEN payment.status = 'CONFIRMED' AND payment.direction = 'CLIENT_TO_AZY'
                               THEN -payment.amount_ariary
                           ELSE 0 END)
                         FROM client_settlement_payment payment
                         WHERE payment.settlement_id = settlement.id), 0)
                         AS confirmed_signed_payments_ariary
                FROM client_daily_settlement settlement
                WHERE settlement.id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> mapSettlement(resultSet), settlementId)
                .stream().findFirst();
    }

    public boolean insertSettlement(ClientDailySettlement settlement) {
        return jdbcTemplate.update("""
                INSERT INTO client_daily_settlement (
                    id, report_id, service_date, closure_revision,
                    client_account_id, client_display_name, amount_due_ariary, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, settlement.id(), settlement.reportId(), Date.valueOf(settlement.serviceDate()),
                settlement.closureRevision(), settlement.clientAccountId(), settlement.clientDisplayName(),
                settlement.amountDueAriary(), timestamp(settlement.createdAt())) == 1;
    }

    public List<ClientDailySettlement> listActiveSettlements(UUID clientAccountId) {
        return jdbcTemplate.query("""
                SELECT settlement.*,
                       COALESCE(SUM(CASE
                           WHEN payment.status = 'CONFIRMED' AND payment.direction = 'AZY_TO_CLIENT'
                               THEN payment.amount_ariary
                           WHEN payment.status = 'CONFIRMED' AND payment.direction = 'CLIENT_TO_AZY'
                               THEN -payment.amount_ariary
                           ELSE 0 END), 0) AS confirmed_signed_payments_ariary
                FROM client_daily_settlement settlement
                JOIN delivery_operation_day operation_day
                  ON operation_day.service_date = settlement.service_date
                 AND operation_day.closure_revision = settlement.closure_revision
                 AND operation_day.status = 'CLOSED'
                LEFT JOIN client_settlement_payment payment
                       ON payment.settlement_id = settlement.id
                WHERE settlement.client_account_id = ?
                GROUP BY settlement.id
                ORDER BY settlement.service_date DESC, settlement.created_at DESC
                """, (resultSet, rowNumber) -> mapSettlement(resultSet), clientAccountId);
    }

    public Optional<PaymentDestination> findPreferredClientPayoutDestination(UUID clientAccountId) {
        return jdbcTemplate.query("""
                SELECT operator, account_holder, number
                FROM client_payout_destination
                WHERE account_id = ? AND active = TRUE
                ORDER BY priority, id
                FETCH FIRST 1 ROW ONLY
                """, (resultSet, rowNumber) -> new PaymentDestination(
                resultSet.getString("operator"),
                resultSet.getString("account_holder"),
                resultSet.getString("number")), clientAccountId).stream().findFirst();
    }

    public void insertClientPayment(ClientPayment payment) {
        jdbcTemplate.update("""
                INSERT INTO client_settlement_payment (
                    id, settlement_id, direction, amount_ariary, payment_method,
                    destination_operator, destination_account_holder, destination_number,
                    transaction_reference, note,
                    proof_storage_key, proof_original_filename, proof_content_type,
                    proof_size_bytes, proof_sha256,
                    status, confirmed_at, confirmed_by_account_id, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 0)
                """, payment.id(), payment.settlementId(), payment.direction().name(), payment.amountAriary(),
                payment.paymentMethod(), destinationOperator(payment.destination()),
                destinationHolder(payment.destination()), destinationNumber(payment.destination()),
                payment.transactionReference(), payment.note(), proofKey(payment.proof()),
                proofFilename(payment.proof()), proofType(payment.proof()), proofSize(payment.proof()),
                proofHash(payment.proof()), timestamp(payment.confirmedAt()), payment.confirmedByAccountId());
    }

    public List<ClientPayment> listClientPayments(UUID clientAccountId) {
        return jdbcTemplate.query("SELECT " + CLIENT_PAYMENT_COLUMNS + """
                FROM client_settlement_payment payment
                JOIN client_daily_settlement settlement ON settlement.id = payment.settlement_id
                WHERE settlement.client_account_id = ?
                ORDER BY payment.confirmed_at DESC, payment.id
                """, (resultSet, rowNumber) -> mapClientPayment(resultSet), clientAccountId);
    }

    public Optional<ClientPayment> findClientPayment(UUID paymentId) {
        return jdbcTemplate.query("SELECT " + CLIENT_PAYMENT_COLUMNS + """
                FROM client_settlement_payment payment
                JOIN client_daily_settlement settlement ON settlement.id = payment.settlement_id
                WHERE payment.id = ?
                """, (resultSet, rowNumber) -> mapClientPayment(resultSet), paymentId)
                .stream().findFirst();
    }

    public Optional<ClientPayment> lockClientPayment(UUID paymentId) {
        return jdbcTemplate.query("SELECT " + CLIENT_PAYMENT_COLUMNS + """
                FROM client_settlement_payment payment
                JOIN client_daily_settlement settlement ON settlement.id = payment.settlement_id
                WHERE payment.id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> mapClientPayment(resultSet), paymentId)
                .stream().findFirst();
    }

    public boolean voidClientPayment(
            UUID paymentId,
            long expectedVersion,
            UUID actorAccountId,
            String reason,
            Instant now) {
        return jdbcTemplate.update("""
                UPDATE client_settlement_payment
                SET status = 'VOIDED', voided_at = ?, voided_by_account_id = ?,
                    void_reason = ?, version = version + 1
                WHERE id = ? AND status = 'CONFIRMED' AND version = ?
                """, timestamp(now), actorAccountId, reason, paymentId, expectedVersion) == 1;
    }

    public boolean clientHasConfirmedPaymentsOnDate(LocalDate serviceDate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM client_settlement_payment payment
                JOIN client_daily_settlement settlement ON settlement.id = payment.settlement_id
                WHERE settlement.service_date = ? AND payment.status = 'CONFIRMED'
                """, Integer.class, Date.valueOf(serviceDate));
        return count != null && count > 0;
    }

    public List<CompensationSourceLine> compensationSourceLines(LocalDate serviceDate) {
        return jdbcTemplate.query("""
                SELECT package.id AS package_id,
                       package.actual_delivery_courier_id AS courier_id,
                       package.actual_delivery_courier_name AS courier_name,
                       package.actual_delivery_courier_contact AS courier_contact,
                       package.final_outcome, package.return_completed_at,
                       COALESCE(package.final_delivery_fee_ariary, package.delivery_fee_ariary)
                           AS delivery_fee_ariary,
                       COALESCE(courier.commission_numerator_override,
                                settings.default_courier_commission_numerator)
                           AS commission_numerator,
                       COALESCE(courier.commission_denominator_override,
                                settings.default_courier_commission_denominator)
                           AS commission_denominator,
                       package.recipient_name, package.destination_label
                FROM delivery_package package
                JOIN dispatch_plan plan ON plan.id = package.dispatch_plan_id
                JOIN courier_profile courier ON courier.id = package.actual_delivery_courier_id
                CROSS JOIN operational_settings settings
                WHERE plan.service_date = ?
                  AND plan.status IN ('READY', 'READY_WITH_WARNINGS')
                  AND plan.lifecycle_status = 'ACTIVE'
                  AND package.final_outcome IN ('DELIVERED', 'RETURN_TO_SENDER')
                ORDER BY package.actual_delivery_courier_name, package.priority_at, package.id
                """, (resultSet, rowNumber) -> new CompensationSourceLine(
                resultSet.getObject("package_id", UUID.class),
                resultSet.getObject("courier_id", UUID.class),
                resultSet.getString("courier_name"),
                resultSet.getString("courier_contact"),
                DeliveryPackageOutcome.valueOf(resultSet.getString("final_outcome")),
                instant(resultSet, "return_completed_at"),
                resultSet.getLong("delivery_fee_ariary"),
                resultSet.getInt("commission_numerator"),
                resultSet.getInt("commission_denominator"),
                resultSet.getString("recipient_name"),
                resultSet.getString("destination_label")), Date.valueOf(serviceDate));
    }

    public Optional<CompensationBatch> findCompensationBatch(LocalDate serviceDate, int closureRevision) {
        return jdbcTemplate.query("""
                SELECT service_date, closure_revision, total_suggested_ariary,
                       total_final_ariary, finalized_at, finalized_by_account_id, version
                FROM courier_compensation_batch
                WHERE service_date = ? AND closure_revision = ?
                """, (resultSet, rowNumber) -> mapCompensationBatch(resultSet),
                Date.valueOf(serviceDate), closureRevision).stream().findFirst();
    }

    public boolean hasCompensationBatchOnDate(LocalDate serviceDate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM courier_compensation_batch WHERE service_date = ?
                """, Integer.class, Date.valueOf(serviceDate));
        return count != null && count > 0;
    }

    public void insertCompensationBatch(CompensationBatch batch) {
        jdbcTemplate.update("""
                INSERT INTO courier_compensation_batch (
                    service_date, closure_revision, total_suggested_ariary,
                    total_final_ariary, finalized_at, finalized_by_account_id, version
                ) VALUES (?, ?, ?, ?, ?, ?, 0)
                """, Date.valueOf(batch.serviceDate()), batch.closureRevision(),
                batch.totalSuggestedAriary(), batch.totalFinalAriary(),
                timestamp(batch.finalizedAt()), batch.finalizedByAccountId());
    }

    public void insertEarning(CourierEarning earning) {
        jdbcTemplate.update("""
                INSERT INTO courier_earning (
                    id, service_date, closure_revision, delivery_package_id,
                    courier_id, courier_name, courier_contact, delivery_outcome,
                    delivery_fee_ariary, commission_numerator, commission_denominator,
                    suggested_amount_ariary, final_amount_ariary, override_reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, earning.id(), Date.valueOf(earning.serviceDate()), earning.closureRevision(),
                earning.packageId(), earning.courierId(), earning.courierName(), earning.courierContact(),
                earning.outcome().name(), earning.deliveryFeeAriary(), earning.commissionNumerator(),
                earning.commissionDenominator(), earning.suggestedAmountAriary(), earning.finalAmountAriary(),
                earning.overrideReason(), timestamp(earning.createdAt()));
    }

    public List<CourierEarning> listBatchEarnings(LocalDate serviceDate, int closureRevision) {
        return jdbcTemplate.query("""
                SELECT * FROM courier_earning
                WHERE service_date = ? AND closure_revision = ?
                ORDER BY courier_name, created_at, id
                """, (resultSet, rowNumber) -> mapEarning(resultSet),
                Date.valueOf(serviceDate), closureRevision);
    }

    public Optional<String> findCourierName(UUID courierId) {
        return jdbcTemplate.query("SELECT name FROM courier_profile WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getString("name"), courierId)
                .stream().findFirst();
    }

    public List<CourierEarning> listCourierEarnings(UUID courierId, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("SELECT * FROM courier_earning WHERE courier_id = :courierId");
        MapSqlParameterSource parameters = new MapSqlParameterSource("courierId", courierId);
        appendDateRange(sql, parameters, "service_date", from, to);
        sql.append(" ORDER BY service_date DESC, created_at DESC, id");
        return namedJdbcTemplate.query(sql.toString(), parameters,
                (resultSet, rowNumber) -> mapEarning(resultSet));
    }

    public List<CourierDelivery> listCourierDeliveries(UUID courierId, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("""
                SELECT package.id AS package_id, plan.service_date,
                       account.display_name AS client_display_name,
                       package.recipient_name, request.pickup_label, package.destination_label,
                       package.final_outcome, package.return_completed_at,
                       COALESCE(package.final_delivery_fee_ariary, package.delivery_fee_ariary, 0)
                           AS delivery_fee_ariary,
                       earning.final_amount_ariary AS earned_amount_ariary
                FROM delivery_package package
                JOIN delivery_request request ON request.id = package.delivery_request_id
                JOIN app_account account ON account.id = request.owner_account_id
                JOIN dispatch_plan plan ON plan.id = package.dispatch_plan_id
                LEFT JOIN courier_earning earning ON earning.delivery_package_id = package.id
                WHERE package.actual_delivery_courier_id = :courierId
                  AND plan.lifecycle_status = 'ACTIVE'
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource("courierId", courierId);
        appendDateRange(sql, parameters, "plan.service_date", from, to);
        sql.append(" ORDER BY plan.service_date DESC, package.outcome_recorded_at DESC, package.id");
        return namedJdbcTemplate.query(sql.toString(), parameters, (resultSet, rowNumber) -> new CourierDelivery(
                resultSet.getObject("package_id", UUID.class),
                resultSet.getObject("service_date", LocalDate.class),
                resultSet.getString("client_display_name"),
                resultSet.getString("recipient_name"),
                resultSet.getString("pickup_label"),
                resultSet.getString("destination_label"),
                DeliveryPackageOutcome.valueOf(resultSet.getString("final_outcome")),
                instant(resultSet, "return_completed_at"),
                resultSet.getLong("delivery_fee_ariary"),
                nullableLong(resultSet, "earned_amount_ariary")));
    }

    public long totalCourierEarnings(UUID courierId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(final_amount_ariary), 0) FROM courier_earning WHERE courier_id = ?",
                Long.class, courierId);
        return total == null ? 0 : total;
    }

    public long totalConfirmedCourierPayments(UUID courierId) {
        Long total = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount_ariary), 0)
                FROM courier_payment WHERE courier_id = ? AND status = 'CONFIRMED'
                """, Long.class, courierId);
        return total == null ? 0 : total;
    }

    public void insertCourierPayment(CourierPayment payment) {
        jdbcTemplate.update("""
                INSERT INTO courier_payment (
                    id, courier_id, amount_ariary, payment_method,
                    destination_operator, destination_account_holder, destination_number,
                    transaction_reference, note,
                    proof_storage_key, proof_original_filename, proof_content_type,
                    proof_size_bytes, proof_sha256,
                    status, confirmed_at, confirmed_by_account_id, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 0)
                """, payment.id(), payment.courierId(), payment.amountAriary(), payment.paymentMethod(),
                destinationOperator(payment.destination()), destinationHolder(payment.destination()),
                destinationNumber(payment.destination()), payment.transactionReference(), payment.note(),
                proofKey(payment.proof()), proofFilename(payment.proof()), proofType(payment.proof()),
                proofSize(payment.proof()), proofHash(payment.proof()), timestamp(payment.confirmedAt()),
                payment.confirmedByAccountId());
    }

    public List<CourierPayment> listCourierPayments(
            UUID courierId,
            Instant fromInclusive,
            Instant toExclusive) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + COURIER_PAYMENT_COLUMNS + " FROM courier_payment payment WHERE courier_id = :courierId");
        MapSqlParameterSource parameters = new MapSqlParameterSource("courierId", courierId);
        if (fromInclusive != null) {
            sql.append(" AND confirmed_at >= :fromInclusive");
            parameters.addValue("fromInclusive", timestamp(fromInclusive));
        }
        if (toExclusive != null) {
            sql.append(" AND confirmed_at < :toExclusive");
            parameters.addValue("toExclusive", timestamp(toExclusive));
        }
        sql.append(" ORDER BY confirmed_at DESC, id");
        return namedJdbcTemplate.query(sql.toString(), parameters,
                (resultSet, rowNumber) -> mapCourierPayment(resultSet));
    }

    public Optional<CourierPayment> findCourierPayment(UUID paymentId) {
        return jdbcTemplate.query("SELECT " + COURIER_PAYMENT_COLUMNS + """
                FROM courier_payment payment WHERE payment.id = ?
                """, (resultSet, rowNumber) -> mapCourierPayment(resultSet), paymentId)
                .stream().findFirst();
    }

    public Optional<CourierPayment> lockCourierPayment(UUID paymentId) {
        return jdbcTemplate.query("SELECT " + COURIER_PAYMENT_COLUMNS + """
                FROM courier_payment payment WHERE payment.id = ? FOR UPDATE
                """, (resultSet, rowNumber) -> mapCourierPayment(resultSet), paymentId)
                .stream().findFirst();
    }

    public boolean voidCourierPayment(
            UUID paymentId,
            long expectedVersion,
            UUID actorAccountId,
            String reason,
            Instant now) {
        return jdbcTemplate.update("""
                UPDATE courier_payment
                SET status = 'VOIDED', voided_at = ?, voided_by_account_id = ?,
                    void_reason = ?, version = version + 1
                WHERE id = ? AND status = 'CONFIRMED' AND version = ?
                """, timestamp(now), actorAccountId, reason, paymentId, expectedVersion) == 1;
    }

    private void appendDateRange(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            String column,
            LocalDate from,
            LocalDate to) {
        if (from != null) {
            sql.append(" AND ").append(column).append(" >= :fromDate");
            parameters.addValue("fromDate", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND ").append(column).append(" <= :toDate");
            parameters.addValue("toDate", Date.valueOf(to));
        }
    }

    private ClosedDay mapClosedDay(ResultSet resultSet) throws SQLException {
        return new ClosedDay(
                resultSet.getObject("service_date", LocalDate.class),
                resultSet.getString("status"),
                resultSet.getInt("closure_revision"));
    }

    private ClientDailySettlement mapSettlement(ResultSet resultSet) throws SQLException {
        return new ClientDailySettlement(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("report_id", UUID.class),
                resultSet.getObject("service_date", LocalDate.class),
                resultSet.getInt("closure_revision"),
                resultSet.getObject("client_account_id", UUID.class),
                resultSet.getString("client_display_name"),
                resultSet.getLong("amount_due_ariary"),
                resultSet.getLong("confirmed_signed_payments_ariary"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private ClientPayment mapClientPayment(ResultSet resultSet) throws SQLException {
        return new ClientPayment(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("settlement_id", UUID.class),
                resultSet.getObject("client_account_id", UUID.class),
                resultSet.getObject("service_date", LocalDate.class),
                ClientPaymentDirection.valueOf(resultSet.getString("direction")),
                resultSet.getLong("amount_ariary"),
                resultSet.getString("payment_method"),
                destination(resultSet), resultSet.getString("transaction_reference"),
                resultSet.getString("note"), proof(resultSet),
                PaymentStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("confirmed_at").toInstant(),
                resultSet.getObject("confirmed_by_account_id", UUID.class),
                instant(resultSet, "voided_at"),
                resultSet.getObject("voided_by_account_id", UUID.class),
                resultSet.getString("void_reason"), resultSet.getLong("version"));
    }

    private CompensationBatch mapCompensationBatch(ResultSet resultSet) throws SQLException {
        return new CompensationBatch(
                resultSet.getObject("service_date", LocalDate.class),
                resultSet.getInt("closure_revision"),
                resultSet.getLong("total_suggested_ariary"),
                resultSet.getLong("total_final_ariary"),
                resultSet.getTimestamp("finalized_at").toInstant(),
                resultSet.getObject("finalized_by_account_id", UUID.class),
                resultSet.getLong("version"));
    }

    private CourierEarning mapEarning(ResultSet resultSet) throws SQLException {
        return new CourierEarning(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("service_date", LocalDate.class),
                resultSet.getInt("closure_revision"),
                resultSet.getObject("delivery_package_id", UUID.class),
                resultSet.getObject("courier_id", UUID.class),
                resultSet.getString("courier_name"),
                resultSet.getString("courier_contact"),
                DeliveryPackageOutcome.valueOf(resultSet.getString("delivery_outcome")),
                resultSet.getLong("delivery_fee_ariary"),
                resultSet.getInt("commission_numerator"),
                resultSet.getInt("commission_denominator"),
                resultSet.getLong("suggested_amount_ariary"),
                resultSet.getLong("final_amount_ariary"),
                resultSet.getString("override_reason"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private CourierPayment mapCourierPayment(ResultSet resultSet) throws SQLException {
        return new CourierPayment(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("courier_id", UUID.class),
                resultSet.getLong("amount_ariary"), resultSet.getString("payment_method"),
                destination(resultSet), resultSet.getString("transaction_reference"),
                resultSet.getString("note"), proof(resultSet),
                PaymentStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("confirmed_at").toInstant(),
                resultSet.getObject("confirmed_by_account_id", UUID.class),
                instant(resultSet, "voided_at"),
                resultSet.getObject("voided_by_account_id", UUID.class),
                resultSet.getString("void_reason"), resultSet.getLong("version"));
    }

    private PaymentDestination destination(ResultSet resultSet) throws SQLException {
        String operator = resultSet.getString("destination_operator");
        return operator == null ? null : new PaymentDestination(
                operator,
                resultSet.getString("destination_account_holder"),
                resultSet.getString("destination_number"));
    }

    private ProofMetadata proof(ResultSet resultSet) throws SQLException {
        String storageKey = resultSet.getString("proof_storage_key");
        return storageKey == null ? null : new ProofMetadata(
                storageKey,
                resultSet.getString("proof_original_filename"),
                resultSet.getString("proof_content_type"),
                resultSet.getLong("proof_size_bytes"),
                resultSet.getString("proof_sha256"));
    }

    private String destinationOperator(PaymentDestination destination) {
        return destination == null ? null : destination.operator();
    }

    private String destinationHolder(PaymentDestination destination) {
        return destination == null ? null : destination.accountHolder();
    }

    private String destinationNumber(PaymentDestination destination) {
        return destination == null ? null : destination.number();
    }

    private String proofKey(ProofMetadata proof) {
        return proof == null ? null : proof.storageKey();
    }

    private String proofFilename(ProofMetadata proof) {
        return proof == null ? null : proof.originalFilename();
    }

    private String proofType(ProofMetadata proof) {
        return proof == null ? null : proof.contentType();
    }

    private Long proofSize(ProofMetadata proof) {
        return proof == null ? null : proof.sizeBytes();
    }

    private String proofHash(ProofMetadata proof) {
        return proof == null ? null : proof.sha256();
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
