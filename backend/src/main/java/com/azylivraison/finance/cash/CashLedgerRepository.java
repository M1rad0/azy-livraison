package com.azylivraison.finance.cash;

import static com.azylivraison.finance.cash.CashLedgerModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.finance.domain.FinanceModels.ProofMetadata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CashLedgerRepository {
    private final JdbcTemplate jdbc;

    public CashLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every writer, including briefing reservations, takes this lock first. */
    public void lock() {
        jdbc.queryForObject("SELECT revision FROM cash_ledger_state WHERE id = 1 FOR UPDATE", Long.class);
    }

    public long revision() {
        return jdbc.queryForObject("SELECT revision FROM cash_ledger_state WHERE id = 1", Long.class);
    }

    public void advanceRevision() {
        jdbc.update("UPDATE cash_ledger_state SET revision = revision + 1 WHERE id = 1");
    }

    /** An ordinary ledger change makes every outstanding proposal stale, not confirmed. */
    void releaseReservations() {
        jdbc.update("UPDATE cash_ledger_reservation SET status = 'RELEASED' WHERE status = 'ACTIVE'");
    }

    /** Expired/invalid plans must not leave unavailable money on the finance screen. */
    void cleanupReservations() {
        jdbc.update("""
                UPDATE cash_briefing_batch b SET status = CASE WHEN expires_at <= CURRENT_TIMESTAMP
                    THEN 'EXPIRED' ELSE 'PLAN_INVALID' END
                WHERE b.status = 'ACTIVE' AND (b.expires_at <= CURRENT_TIMESTAMP OR NOT EXISTS (
                    SELECT 1 FROM dispatch_plan p JOIN delivery_operation_day d ON d.dispatch_plan_id = p.id
                    WHERE p.id = b.plan_id AND p.status IN ('READY', 'READY_WITH_WARNINGS')
                        AND p.archived_at IS NULL AND d.status <> 'CLOSED'))
                """);
        jdbc.update("""
                UPDATE cash_ledger_reservation r SET status = 'RELEASED'
                WHERE r.status = 'ACTIVE' AND EXISTS (
                    SELECT 1 FROM cash_briefing_transfer t JOIN cash_briefing_batch b ON b.id = t.batch_id
                    WHERE t.id = r.proposal_id AND b.status <> 'ACTIVE')
                """);
    }

    /** Create zero accounts for profiles created after the accounting cutover. */
    public void ensureAccounts() {
        jdbc.update("""
                INSERT INTO cash_ledger_account (account_key, account_type, entity_id)
                SELECT 'COURIER:' || CAST(c.id AS VARCHAR), 'COURIER', c.id
                FROM courier_profile c WHERE NOT EXISTS (
                    SELECT 1 FROM cash_ledger_account a WHERE a.account_type = 'COURIER' AND a.entity_id = c.id)
                """);
        jdbc.update("""
                INSERT INTO cash_ledger_account (account_key, account_type, entity_id)
                SELECT 'SELLER:' || CAST(c.id AS VARCHAR), 'SELLER', c.id
                FROM app_account c WHERE c.role = 'CLIENT' AND NOT EXISTS (
                    SELECT 1 FROM cash_ledger_account a WHERE a.account_type = 'SELLER' AND a.entity_id = c.id)
                """);
    }

    public Snapshot snapshot() {
        return snapshot(true);
    }

    public Snapshot snapshot(boolean includeHistory) {
        Instant activatedAt = jdbc.queryForObject(
                "SELECT activated_at FROM cash_ledger_state WHERE id = 1", (rs, n) -> rs.getTimestamp(1).toInstant());
        return new Snapshot(activatedAt, revision(), accounts(),
                includeHistory ? movements() : List.of(), includeHistory ? obligations() : List.of());
    }

    public List<Account> accounts() {
        return jdbc.query("""
                SELECT a.*, COALESCE(c.name, s.display_name, 'AZY') AS account_name,
                       CASE WHEN a.account_type = 'COURIER' THEN c.payout_number ELSE (
                           SELECT p.number FROM client_payout_destination p
                           WHERE p.account_id = a.entity_id AND p.active = TRUE
                           ORDER BY p.priority FETCH FIRST 1 ROW ONLY) END AS payout_number,
                       COALESCE((SELECT SUM(l.delta_ariary) FROM cash_ledger_leg l
                           WHERE l.account_key = a.account_key), 0) AS balance,
                       COALESCE((SELECT SUM(r.amount_ariary) FROM cash_ledger_reservation r
                           WHERE r.account_key = a.account_key AND r.status = 'ACTIVE'), 0) AS reserved
                FROM cash_ledger_account a
                LEFT JOIN courier_profile c ON a.account_type = 'COURIER' AND c.id = a.entity_id
                LEFT JOIN app_account s ON a.account_type = 'SELLER' AND s.id = a.entity_id
                ORDER BY a.account_type, account_name, a.account_key
                """, (rs, n) -> {
                    long balance = rs.getBigDecimal("balance").longValueExact();
                    long reserved = rs.getBigDecimal("reserved").longValueExact();
                    return new Account(rs.getString("account_key"), rs.getString("account_type"),
                            rs.getObject("entity_id", UUID.class), rs.getString("account_name"),
                            balance, reserved, Math.subtractExact(balance, reserved), rs.getString("payout_number"));
                });
    }

    public Optional<Account> account(String key) {
        return accounts().stream().filter(a -> a.key().equals(key)).findFirst();
    }

    public List<Movement> movements() {
        return jdbc.query("""
                SELECT m.*, v.note AS void_reason, v.created_at AS voided_at
                FROM cash_ledger_movement m
                LEFT JOIN cash_ledger_movement v ON v.reversal_of = m.id
                ORDER BY m.created_at DESC, m.id
                """, (rs, n) -> movement(rs));
    }

    public CashPage<Movement> movements(CashListQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (query.from() != null) { where.append(" AND m.created_at >= ?"); args.add(query.fromTimestamp()); }
        if (query.to() != null) { where.append(" AND m.created_at < ?"); args.add(query.untilTimestamp()); }
        if (query.hasSearch()) {
            where.append("""
                     AND (LOWER(COALESCE(m.transaction_reference, '')) LIKE ? ESCAPE '!'
                       OR LOWER(COALESCE(m.note, '')) LIKE ? ESCAPE '!'
                       OR LOWER(COALESCE(m.payment_method, '')) LIKE ? ESCAPE '!'
                       OR LOWER(COALESCE(m.from_key, '')) LIKE ? ESCAPE '!'
                       OR LOWER(COALESCE(m.to_key, '')) LIKE ? ESCAPE '!'
                       OR EXISTS (SELECT 1 FROM cash_ledger_account a
                           LEFT JOIN courier_profile c ON a.account_type = 'COURIER' AND c.id = a.entity_id
                           LEFT JOIN app_account s ON a.account_type = 'SELLER' AND s.id = a.entity_id
                           WHERE a.account_key IN (m.from_key, m.to_key)
                             AND LOWER(COALESCE(c.name, s.display_name, 'AZY')) LIKE ? ESCAPE '!'))
                    """);
            for (int i = 0; i < 6; i++) args.add(query.searchPattern());
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM cash_ledger_movement m" + where,
                Long.class, args.toArray());
        args.add(query.size()); args.add(query.offset());
        List<Movement> items = jdbc.query("""
                SELECT m.*, v.note AS void_reason, v.created_at AS voided_at
                FROM cash_ledger_movement m LEFT JOIN cash_ledger_movement v ON v.reversal_of = m.id
                """ + where + " ORDER BY m.created_at DESC, m.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> movement(rs), args.toArray());
        return new CashPage<>(items, total, query);
    }

    public Optional<Movement> movement(UUID id) {
        return jdbc.query("""
                SELECT m.*, v.note AS void_reason, v.created_at AS voided_at
                FROM cash_ledger_movement m
                LEFT JOIN cash_ledger_movement v ON v.reversal_of = m.id WHERE m.id = ?
                """, (rs, n) -> movement(rs), id).stream().findFirst();
    }

    private Movement movement(ResultSet rs) throws SQLException {
        return new Movement(rs.getObject("id", UUID.class), rs.getString("kind"),
                rs.getString("from_key"), rs.getString("to_key"), rs.getLong("amount_ariary"),
                rs.getObject("delta_ariary", Long.class), rs.getString("payment_method"),
                rs.getString("transaction_reference"), rs.getString("note"),
                rs.getTimestamp("voided_at") == null ? "CONFIRMED" : "VOIDED",
                rs.getTimestamp("created_at").toInstant(), rs.getObject("actor_account_id", UUID.class),
                rs.getString("proof_storage_key") != null, rs.getObject("reversal_of", UUID.class),
                rs.getString("void_reason"), rs.getTimestamp("voided_at") == null
                        ? null : rs.getTimestamp("voided_at").toInstant());
    }

    Optional<MovementEntry> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM cash_ledger_movement WHERE idempotency_key = ?",
                (rs, n) -> entry(rs), key).stream().findFirst();
    }

    Optional<MovementEntry> entry(UUID id) {
        return jdbc.query("SELECT * FROM cash_ledger_movement WHERE id = ?",
                (rs, n) -> entry(rs), id).stream().findFirst();
    }

    private MovementEntry entry(ResultSet rs) throws SQLException {
        ProofMetadata proof = rs.getString("proof_storage_key") == null ? null : new ProofMetadata(
                rs.getString("proof_storage_key"), rs.getString("proof_original_filename"),
                rs.getString("proof_content_type"), rs.getLong("proof_size_bytes"), rs.getString("proof_sha256"));
        return new MovementEntry(rs.getObject("id", UUID.class), rs.getString("kind"),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getString("from_key"), rs.getString("to_key"), rs.getLong("amount_ariary"),
                rs.getObject("delta_ariary", Long.class), rs.getString("payment_method"),
                rs.getString("transaction_reference"), rs.getString("note"),
                rs.getObject("source_reference", UUID.class), rs.getObject("reversal_of", UUID.class),
                rs.getObject("actor_account_id", UUID.class), proof);
    }

    void insert(MovementEntry entry, List<Leg> legs) {
        ProofMetadata proof = entry.proof();
        jdbc.update("""
                INSERT INTO cash_ledger_movement (id, kind, idempotency_key, request_fingerprint,
                    from_key, to_key, amount_ariary, delta_ariary, payment_method, transaction_reference,
                    note, source_reference, reversal_of, actor_account_id, proof_storage_key,
                    proof_original_filename, proof_content_type, proof_size_bytes, proof_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entry.id(), entry.kind(), entry.idempotencyKey(), entry.fingerprint(), entry.fromKey(),
                entry.toKey(), entry.amountAriary(), entry.deltaAriary(), entry.paymentMethod(), entry.reference(),
                entry.note(), entry.sourceReference(), entry.reversalOf(), entry.actorAccountId(),
                proof == null ? null : proof.storageKey(), proof == null ? null : proof.originalFilename(),
                proof == null ? null : proof.contentType(), proof == null ? null : proof.sizeBytes(),
                proof == null ? null : proof.sha256());
        for (Leg leg : legs) {
            if (leg.deltaAriary() != 0) {
                jdbc.update("INSERT INTO cash_ledger_leg (movement_id, account_key, delta_ariary) VALUES (?, ?, ?)",
                        entry.id(), leg.accountKey(), leg.deltaAriary());
            }
        }
    }

    List<Leg> legs(UUID movementId) {
        return jdbc.query("SELECT account_key, delta_ariary FROM cash_ledger_leg WHERE movement_id = ?",
                (rs, n) -> new Leg(rs.getString(1), rs.getLong(2)), movementId);
    }

    Optional<CollectionState> collection(UUID packageId) {
        return jdbc.query("SELECT * FROM cash_ledger_collection_state WHERE package_id = ?", (rs, n) ->
                new CollectionState(packageId, rs.getObject("courier_id", UUID.class),
                        rs.getLong("observed_amount_ariary"), rs.getObject("baseline_courier_id", UUID.class),
                        rs.getLong("baseline_amount_ariary"), rs.getLong("version")), packageId).stream().findFirst();
    }

    void saveCollection(CollectionState state) {
        if (jdbc.update("""
                UPDATE cash_ledger_collection_state SET courier_id = ?, observed_amount_ariary = ?,
                    version = ?, updated_at = CURRENT_TIMESTAMP WHERE package_id = ?
                """, state.courierId(), state.observedAmountAriary(), state.version(), state.packageId()) == 0) {
            jdbc.update("""
                    INSERT INTO cash_ledger_collection_state (package_id, courier_id, observed_amount_ariary,
                        baseline_courier_id, baseline_amount_ariary, version) VALUES (?, ?, ?, ?, ?, ?)
                    """, state.packageId(), state.courierId(), state.observedAmountAriary(),
                    state.baselineCourierId(), state.baselineAmountAriary(), state.version());
        }
    }

    Optional<ObligationState> obligation(UUID reportId, UUID seller) {
        return jdbc.query("SELECT * FROM cash_ledger_obligation WHERE report_id = ? AND seller_account_id = ?",
                (rs, n) -> obligationState(rs), reportId, seller).stream().findFirst();
    }

    /** Opening baselines follow later report revisions of the same seller/day. */
    long openingSellerBaseline(LocalDate date, UUID seller) {
        return jdbc.query("""
                SELECT o.baseline_amount_ariary FROM cash_ledger_obligation o
                JOIN client_daily_settlement s ON s.id = o.id
                WHERE o.service_date = ? AND o.seller_account_id = ?
                ORDER BY s.closure_revision DESC FETCH FIRST 1 ROW ONLY
                """, (rs, n) -> rs.getLong(1), date, seller).stream().findFirst().orElse(0L);
    }

    List<ObligationState> reportObligations(UUID reportId) {
        return jdbc.query("SELECT * FROM cash_ledger_obligation WHERE report_id = ?",
                (rs, n) -> obligationState(rs), reportId);
    }

    private ObligationState obligationState(ResultSet rs) throws SQLException {
        return new ObligationState(rs.getObject("id", UUID.class), rs.getObject("report_id", UUID.class),
                rs.getObject("seller_account_id", UUID.class), rs.getObject("service_date", LocalDate.class),
                rs.getLong("baseline_amount_ariary"), rs.getLong("observed_amount_ariary"),
                rs.getLong("recognized_amount_ariary"), rs.getBoolean("active"), rs.getLong("version"));
    }

    void saveObligation(ObligationState state) {
        if (jdbc.update("""
                UPDATE cash_ledger_obligation SET observed_amount_ariary = ?, recognized_amount_ariary = ?,
                    active = ?, version = ? WHERE id = ?
                """, state.observedAmountAriary(), state.recognizedAmountAriary(), state.active(),
                state.version(), state.id()) == 0) {
            jdbc.update("""
                    INSERT INTO cash_ledger_obligation (id, report_id, seller_account_id, service_date,
                        baseline_amount_ariary, observed_amount_ariary, recognized_amount_ariary, active, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, state.id(), state.reportId(), state.sellerAccountId(), state.serviceDate(),
                    state.baselineAmountAriary(), state.observedAmountAriary(), state.recognizedAmountAriary(),
                    state.active(), state.version());
        }
    }

    public List<Obligation> obligations() {
        return jdbc.query("""
                SELECT o.*, COALESCE((SELECT SUM(a.signed_amount_ariary) FROM cash_ledger_allocation a
                    WHERE a.obligation_id = o.id), 0) AS paid FROM cash_ledger_obligation o
                ORDER BY o.service_date, o.created_at, o.id
                """, (rs, n) -> obligation(rs));
    }

    public CashPage<Obligation> obligations(CashListQuery query) {
        StringBuilder where = new StringBuilder(" WHERE o.active = TRUE");
        List<Object> args = new ArrayList<>();
        if (query.from() != null) { where.append(" AND o.service_date >= ?"); args.add(query.from()); }
        if (query.to() != null) { where.append(" AND o.service_date <= ?"); args.add(query.to()); }
        if (query.hasSearch()) {
            where.append("""
                     AND (EXISTS (SELECT 1 FROM app_account a WHERE a.id = o.seller_account_id
                                AND LOWER(COALESCE(a.display_name, a.username)) LIKE ? ESCAPE '!')
                       OR EXISTS (SELECT 1 FROM client_payout_destination p WHERE p.account_id = o.seller_account_id
                                AND p.active = TRUE AND %s LIKE ? ESCAPE '!'))
                    """.formatted(CashListQuery.phoneSearchExpression("p.number")));
            args.add(query.searchPattern()); args.add(query.phoneSearchPattern());
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM cash_ledger_obligation o" + where,
                Long.class, args.toArray());
        args.add(query.size()); args.add(query.offset());
        List<Obligation> items = jdbc.query("""
                SELECT o.*, COALESCE((SELECT SUM(a.signed_amount_ariary) FROM cash_ledger_allocation a
                    WHERE a.obligation_id = o.id), 0) AS paid FROM cash_ledger_obligation o
                """ + where + " ORDER BY o.service_date DESC, o.created_at DESC, o.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> obligation(rs), args.toArray());
        return new CashPage<>(items, total, query);
    }

    private Obligation obligation(ResultSet rs) throws SQLException {
        long amount = rs.getLong("recognized_amount_ariary");
        long paid = rs.getBigDecimal("paid").longValueExact();
        return new Obligation(rs.getObject("id", UUID.class), rs.getObject("report_id", UUID.class),
                rs.getObject("seller_account_id", UUID.class), rs.getObject("service_date", LocalDate.class),
                amount, paid, Math.subtractExact(amount, paid), rs.getBoolean("active"));
    }

    void allocate(UUID movementId, UUID obligationId, long amount) {
        jdbc.update("INSERT INTO cash_ledger_allocation (movement_id, obligation_id, signed_amount_ariary) VALUES (?, ?, ?)",
                movementId, obligationId, amount);
    }

    /** Confirmed advances may be attached to reports closed after the transfer. */
    List<SellerPayment> unallocatedSellerPayments(UUID seller) {
        return jdbc.query("""
                SELECT m.id, l.delta_ariary, COALESCE((
                    SELECT SUM(a.signed_amount_ariary) FROM cash_ledger_allocation a
                    JOIN cash_ledger_obligation o ON o.id = a.obligation_id
                    WHERE a.movement_id = m.id AND o.seller_account_id = ?), 0) AS allocated
                FROM cash_ledger_movement m JOIN cash_ledger_leg l ON l.movement_id = m.id
                WHERE l.account_key = ? AND m.kind IN ('TRANSFER', 'ADJUSTMENT')
                    AND NOT EXISTS (SELECT 1 FROM cash_ledger_movement reversal WHERE reversal.reversal_of = m.id)
                ORDER BY m.created_at, m.id
                """, (rs, n) -> new SellerPayment(rs.getObject("id", UUID.class),
                        Math.subtractExact(-rs.getLong("delta_ariary"), rs.getBigDecimal("allocated").longValueExact())),
                seller, "SELLER:" + seller).stream().filter(p -> p.unallocatedSignedAriary() != 0).toList();
    }

    void reverseAllocations(UUID original, UUID reversal) {
        jdbc.update("""
                INSERT INTO cash_ledger_allocation (movement_id, obligation_id, signed_amount_ariary)
                SELECT ?, obligation_id, -signed_amount_ariary FROM cash_ledger_allocation WHERE movement_id = ?
                """, reversal, original);
    }

    boolean hasPayments(UUID reportId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM cash_ledger_obligation o WHERE o.report_id = ?
                AND COALESCE((SELECT SUM(a.signed_amount_ariary) FROM cash_ledger_allocation a
                    WHERE a.obligation_id = o.id), 0) <> 0
                """, Long.class, reportId) > 0;
    }
}
