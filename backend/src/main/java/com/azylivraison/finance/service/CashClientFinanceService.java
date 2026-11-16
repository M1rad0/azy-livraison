package com.azylivraison.finance.service;

import static com.azylivraison.finance.domain.FinanceModels.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.finance.cash.CashLedgerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read compatibility for client reports. New cash payments never become legacy writes. */
@Service
public class CashClientFinanceService {
    private final FinancialSettlementService legacy;
    private final CashLedgerService cash;
    private final JdbcTemplate jdbc;

    public CashClientFinanceService(FinancialSettlementService legacy, CashLedgerService cash, JdbcTemplate jdbc) {
        this.legacy = legacy;
        this.cash = cash;
        this.jdbc = jdbc;
    }

    @Transactional
    public ClientFinance finance(UUID accountId) {
        cash.lock();
        ClientFinance old = legacy.getClientFinance(accountId);
        List<ClientDailySettlement> settlements = new ArrayList<>();
        List<ClientPayment> payments = new ArrayList<>(old.payments());
        long totalDue = 0, legacyRemaining = 0;
        for (ClientDailySettlement settlement : old.settlements()) {
            Optional<EpochReport> epoch = epochReport(settlement, accountId);
            boolean managed = epoch.map(report -> report.managed(settlement.id())).orElse(false);
            long cashPaid = newPayments(settlement, accountId, payments);
            // The opening baseline is a historical credit in this reader only.
            // It never becomes a new payment or a cash-ledger leg. It replaces,
            // rather than duplicates, legacy payments on the same migrated report.
            long priorCredit = managed ? epoch.orElseThrow().baselineAriary()
                    : settlement.confirmedSignedPaymentsAriary();
            long paid = Math.addExact(priorCredit, cashPaid);
            settlements.add(new ClientDailySettlement(settlement.id(), settlement.reportId(),
                    settlement.serviceDate(), settlement.closureRevision(), accountId,
                    settlement.clientDisplayName(), settlement.amountDueAriary(), paid, settlement.createdAt()));
            totalDue = Math.addExact(totalDue, settlement.amountDueAriary());
            if (!managed) legacyRemaining = Math.addExact(legacyRemaining, settlement.remainingAriary());
        }
        Long currentCashBalance = jdbc.queryForObject("""
                SELECT COALESCE(SUM(delta_ariary), 0) FROM cash_ledger_leg WHERE account_key = ?
                """, Long.class, "SELLER:" + accountId);
        // Include confirmed advances and reconciliations even before a report exists.
        // Historic unpaid reports remain separate; migrated opening balances were zero.
        long balance = Math.addExact(legacyRemaining, currentCashBalance == null ? 0 : currentCashBalance);
        long totalPaid = Math.subtractExact(totalDue, balance);
        return new ClientFinance(accountId, old.preferredPayoutDestination(), totalDue, totalPaid, balance,
                Math.max(balance, 0), Math.max(Math.negateExact(balance), 0), settlements, payments);
    }

    private record EpochReport(UUID id, long baselineAriary, long recognizedAriary) {
        boolean managed(UUID settlementId) { return !id.equals(settlementId) || recognizedAriary != 0; }
    }

    private Optional<EpochReport> epochReport(ClientDailySettlement settlement, UUID accountId) {
        return jdbc.query("""
                SELECT id, baseline_amount_ariary, recognized_amount_ariary FROM cash_ledger_obligation
                WHERE report_id = ? AND seller_account_id = ? AND active = TRUE
                """, (rs, n) -> new EpochReport(rs.getObject("id", UUID.class),
                        rs.getLong("baseline_amount_ariary"), rs.getLong("recognized_amount_ariary")),
                settlement.reportId(), accountId).stream().findFirst();
    }

    private long newPayments(ClientDailySettlement settlement, UUID accountId, List<ClientPayment> payments) {
        Long paid = jdbc.queryForObject("""
                SELECT COALESCE(SUM(a.signed_amount_ariary), 0)
                FROM cash_ledger_allocation a JOIN cash_ledger_obligation o ON o.id = a.obligation_id
                WHERE o.report_id = ? AND o.seller_account_id = ? AND o.active = TRUE
                """, Long.class, settlement.reportId(), accountId);
        payments.addAll(jdbc.query("""
                SELECT m.*, a.signed_amount_ariary, v.note AS void_reason, v.created_at AS voided_at,
                       v.actor_account_id AS voided_by
                FROM cash_ledger_allocation a
                JOIN cash_ledger_obligation o ON o.id = a.obligation_id
                JOIN cash_ledger_movement m ON m.id = a.movement_id
                LEFT JOIN cash_ledger_movement v ON v.reversal_of = m.id
                WHERE o.report_id = ? AND o.seller_account_id = ? AND o.active = TRUE
                  AND m.kind <> 'REVERSAL'
                ORDER BY m.created_at DESC, m.id
                """, (rs, n) -> {
                    long amount = rs.getLong("signed_amount_ariary");
                    ProofMetadata proof = rs.getString("proof_storage_key") == null ? null : new ProofMetadata(
                            rs.getString("proof_storage_key"), rs.getString("proof_original_filename"),
                            rs.getString("proof_content_type"), rs.getLong("proof_size_bytes"), rs.getString("proof_sha256"));
                    boolean voided = rs.getTimestamp("voided_at") != null;
                    return new ClientPayment(rs.getObject("id", UUID.class), settlement.id(), accountId,
                            settlement.serviceDate(), amount >= 0 ? ClientPaymentDirection.AZY_TO_CLIENT : ClientPaymentDirection.CLIENT_TO_AZY,
                            Math.abs(amount), rs.getString("payment_method") == null ? "Régularisation" : rs.getString("payment_method"),
                            null, rs.getString("transaction_reference"), rs.getString("note"), proof,
                            voided ? PaymentStatus.VOIDED : PaymentStatus.CONFIRMED,
                            rs.getTimestamp("created_at").toInstant(), rs.getObject("actor_account_id", UUID.class),
                            voided ? rs.getTimestamp("voided_at").toInstant() : null,
                            rs.getObject("voided_by", UUID.class), rs.getString("void_reason"), 0);
                }, settlement.reportId(), accountId));
        return paid == null ? 0 : paid;
    }

    @Transactional(readOnly = true)
    public ProofDownload proof(UUID movementId, UUID accountId) {
        Integer owned = jdbc.queryForObject("""
                SELECT COUNT(*) FROM cash_ledger_allocation a
                JOIN cash_ledger_obligation o ON o.id = a.obligation_id
                WHERE a.movement_id = ? AND o.seller_account_id = ?
                """, Integer.class, movementId, accountId);
        return owned != null && owned > 0
                ? cash.getProof(movementId) : legacy.getClientPaymentProof(movementId, accountId);
    }
}
