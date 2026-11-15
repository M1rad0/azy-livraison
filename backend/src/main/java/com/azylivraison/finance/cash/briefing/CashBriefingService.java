package com.azylivraison.finance.cash.briefing;

import static com.azylivraison.finance.cash.briefing.CashBriefingModels.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.azylivraison.dispatch.domain.ManualDispatchDraft;
import com.azylivraison.dispatch.repository.ManualDispatchDraftRepository;
import com.azylivraison.finance.cash.CashLedgerModels;
import com.azylivraison.finance.cash.CashLedgerService;
import com.azylivraison.finance.service.FinanceConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** One shared, persisted proposal. Reading/copying a briefing never transfers money. */
@Service
public class CashBriefingService {
    private final JdbcTemplate jdbc;
    private final CashLedgerService ledger;
    private final ManualDispatchDraftRepository drafts;
    private final CashPayoutOptimizer optimizer = new CashPayoutOptimizer();

    public CashBriefingService(JdbcTemplate jdbc, CashLedgerService ledger, ManualDispatchDraftRepository drafts) {
        this.jdbc = jdbc; this.ledger = ledger; this.drafts = drafts;
    }

    @Transactional
    public List<Batch> list(UUID planId) {
        ledger.lock(); cleanup();
        return jdbc.query("SELECT id FROM cash_briefing_batch "
                + (planId == null ? "WHERE status = 'ACTIVE' " : "WHERE plan_id = ? ")
                + "ORDER BY created_at DESC, id DESC", (rs, n) -> rs.getObject(1, UUID.class),
                planId == null ? new Object[] {} : new Object[] {planId}).stream().map(this::read).toList();
    }

    @Transactional
    public Batch prepare(PrepareRequest request, UUID actor) {
        ledger.lock(); cleanup();
        var replay = jdbc.query("SELECT id, plan_id FROM cash_briefing_batch WHERE actor_account_id = ? AND idempotency_key = ?",
                (rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, actor, request.idempotencyKey());
        if (!replay.isEmpty()) {
            if (!replay.getFirst()[1].equals(request.planId())) conflict("Cette clé de préparation a déjà été utilisée pour un autre plan.");
            return read(replay.getFirst()[0]);
        }
        ManualDispatchDraft draft = activeDraft(request.planId());
        var existing = jdbc.query("SELECT id FROM cash_briefing_batch WHERE plan_id = ? AND status = 'ACTIVE'",
                (rs, n) -> rs.getObject(1, UUID.class), request.planId());
        if (!existing.isEmpty()) return read(existing.getFirst());
        CashLedgerModels.Snapshot snapshot = ledger.getSnapshot();
        List<CashPayoutOptimizer.Courier> couriers = couriers(draft, snapshot);
        Set<UUID> reservedSellers = new HashSet<>(jdbc.query("""
                SELECT DISTINCT t.seller_account_id FROM cash_briefing_transfer t
                JOIN cash_briefing_batch b ON b.id = t.batch_id
                WHERE b.status = 'ACTIVE' AND t.status = 'PENDING'
                """, (rs, n) -> rs.getObject(1, UUID.class)));
        Map<UUID, LocalDate> oldest = new HashMap<>();
        snapshot.obligations().stream().filter(o -> o.active() && o.remainingAriary() > 0)
                .forEach(o -> oldest.merge(o.sellerAccountId(), o.serviceDate(), (a, b) -> a.isBefore(b) ? a : b));
        List<CashPayoutOptimizer.Seller> sellers = snapshot.accounts().stream()
                .filter(a -> "SELLER".equals(a.type()) && a.balanceAriary() > 0 && !reservedSellers.contains(a.entityId()))
                .filter(a -> a.payoutNumber() != null && !a.payoutNumber().isBlank())
                .map(a -> new CashPayoutOptimizer.Seller(a.entityId(), a.name(), a.payoutNumber(), a.balanceAriary(),
                        oldest.getOrDefault(a.entityId(), LocalDate.MAX))).toList();
        List<CashPayoutOptimizer.Allocation> allocations = optimizer.optimize(sellers, couriers);
        UUID id = UUID.randomUUID();
        // A proposal lasts until local end-of-day, never beyond the operating day's closure.
        LocalDate today = LocalDate.now(ZoneId.of("Indian/Antananarivo"));
        Instant expires = (draft.serviceDate().isBefore(today) ? today : draft.serviceDate())
                .plusDays(1).atStartOfDay(ZoneId.of("Indian/Antananarivo")).toInstant();
        jdbc.update("""
                INSERT INTO cash_briefing_batch (id, plan_id, service_date, ledger_revision, status,
                    actor_account_id, idempotency_key, expires_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, id, draft.finalPlanId(), draft.serviceDate(), snapshot.revision(), actor,
                request.idempotencyKey(), java.sql.Timestamp.from(expires));
        for (var allocation : allocations) {
            UUID transferId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO cash_briefing_transfer (id, batch_id, seller_account_id, seller_name,
                        seller_payout_number, courier_account_id, courier_name, amount_ariary, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    """, transferId, id, allocation.seller().id(), allocation.seller().name(),
                    allocation.seller().payoutNumber(), allocation.courier().id(), allocation.courier().name(), allocation.amount());
            reserve(transferId, allocation.courier().id(), allocation.amount());
        }
        return read(id);
    }

    @Transactional
    public Batch release(UUID id) {
        ledger.lock(); cleanup();
        Batch batch = read(id);
        if ("ACTIVE".equals(batch.status()) || "STALE".equals(batch.status())) {
            jdbc.update("UPDATE cash_briefing_batch SET status = 'RELEASED' WHERE id = ?", id);
            releaseReservations(id);
        }
        return read(id);
    }

    @Transactional
    public Batch revalidate(UUID id) {
        ledger.lock(); cleanup();
        Batch batch = read(id);
        if (!List.of("ACTIVE", "STALE").contains(batch.status())) conflict("Cette proposition a été libérée ou n'est plus active.");
        ManualDispatchDraft draft = activeDraft(batch.planId());
        releaseReservations(id);
        CashLedgerModels.Snapshot snapshot = ledger.getSnapshot();
        Map<UUID, Long> available = new HashMap<>();
        couriers(draft, snapshot).forEach(c -> available.put(c.id(), c.available()));
        Map<UUID, Long> sellerDebt = new HashMap<>();
        snapshot.accounts().stream().filter(a -> "SELLER".equals(a.type())).forEach(a -> sellerDebt.put(a.entityId(), a.balanceAriary()));
        Map<UUID, String> payoutNumbers = new HashMap<>();
        snapshot.accounts().stream().filter(a -> "SELLER".equals(a.type()) && a.payoutNumber() != null)
                .forEach(a -> payoutNumbers.put(a.entityId(), a.payoutNumber()));
        Map<UUID, Long> promised = new HashMap<>();
        for (Transfer transfer : batch.transfers()) {
            if (!"PENDING".equals(transfer.status())) continue;
            long left = available.getOrDefault(transfer.courierAccountId(), 0L) - transfer.amountAriary();
            if (left < 0) conflict("La caisse d'un livreur ne couvre plus les versements. Libérez puis préparez une nouvelle proposition.");
            available.put(transfer.courierAccountId(), left);
            promised.merge(transfer.sellerAccountId(), transfer.amountAriary(), Math::addExact);
        }
        for (var entry : promised.entrySet()) {
            if (!Objects.equals(entry.getValue(), sellerDebt.getOrDefault(entry.getKey(), 0L)))
                conflict("Le solde d'un vendeur a changé. Libérez puis préparez une nouvelle proposition.");
            if (!payoutNumbers.containsKey(entry.getKey()) || payoutNumbers.get(entry.getKey()).isBlank())
                conflict("Un vendeur n'a plus de numéro de versement. Renseignez-le avant de revalider.");
        }
        for (Transfer transfer : batch.transfers()) if ("PENDING".equals(transfer.status())) {
            jdbc.update("UPDATE cash_briefing_transfer SET seller_payout_number = ? WHERE id = ?",
                    payoutNumbers.get(transfer.sellerAccountId()), transfer.id());
            reserve(transfer.id(), transfer.courierAccountId(), transfer.amountAriary());
        }
        jdbc.update("UPDATE cash_briefing_batch SET ledger_revision = ? WHERE id = ?", snapshot.revision(), id);
        return read(id);
    }

    @Transactional
    public Batch confirm(UUID transferId, ConfirmRequest request, UUID actor, MultipartFile proof) {
        ledger.lock(); cleanup();
        UUID batchId = jdbc.query("SELECT batch_id FROM cash_briefing_transfer WHERE id = ?",
                (rs, n) -> rs.getObject(1, UUID.class), transferId).stream().findFirst()
                .orElseThrow(() -> new FinanceConflictException("Ce versement n'existe plus."));
        Batch batch = read(batchId);
        Transfer transfer = batch.transfers().stream().filter(t -> t.id().equals(transferId)).findFirst().orElseThrow();
        if ("CONFIRMED".equals(transfer.status())) return batch; // Idempotent double click, even after revision moves.
        if (!"ACTIVE".equals(batch.status()) || request.expectedLedgerRevision() != ledger.revision())
            conflict("Les caisses ont changé. Revalidez la proposition avant de confirmer un versement.");
        activeDraft(batch.planId());
        if (!"PENDING".equals(transfer.status())) conflict("Ce versement n'est plus proposé.");
        int released = jdbc.update("UPDATE cash_ledger_reservation SET status = 'CONFIRMED' WHERE proposal_id = ? AND status = 'ACTIVE'", transferId);
        if (released != 1) conflict("La réservation n'est plus valide. Revalidez la proposition.");
        var movement = ledger.confirmTransferInternal(new CashLedgerModels.TransferRequest("briefing-transfer:" + transferId,
                "COURIER:" + transfer.courierAccountId(), "SELLER:" + transfer.sellerAccountId(), transfer.amountAriary(),
                request.paymentMethod(), request.reference(), request.note(), null), actor, proof);
        jdbc.update("UPDATE cash_briefing_transfer SET status = 'CONFIRMED', movement_id = ? WHERE id = ?", movement.id(), transferId);
        // This known, reserved transfer cannot invalidate the other allocations' budgets.
        jdbc.update("UPDATE cash_briefing_batch SET ledger_revision = ? WHERE status = 'ACTIVE' AND ledger_revision = ?",
                ledger.revision(), batch.ledgerRevision());
        if (jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_transfer WHERE batch_id = ? AND status = 'PENDING'", Long.class, batchId) == 0)
            jdbc.update("UPDATE cash_briefing_batch SET status = 'COMPLETED' WHERE id = ?", batchId);
        return read(batchId);
    }

    private List<CashPayoutOptimizer.Courier> couriers(ManualDispatchDraft draft, CashLedgerModels.Snapshot snapshot) {
        Set<UUID> ids = new HashSet<>();
        draft.couriers().forEach(c -> ids.add(c.courierId()));
        return snapshot.accounts().stream().filter(a -> "COURIER".equals(a.type()) && ids.contains(a.entityId()))
                .filter(a -> a.availableAriary() > 0)
                .map(a -> new CashPayoutOptimizer.Courier(a.entityId(), a.name(), a.availableAriary())).toList();
    }

    private ManualDispatchDraft activeDraft(UUID planId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dispatch_plan p JOIN delivery_operation_day d ON d.dispatch_plan_id = p.id
                WHERE p.id = ? AND p.status IN ('READY', 'READY_WITH_WARNINGS') AND p.mode = 'MANUAL'
                    AND p.archived_at IS NULL AND d.status <> 'CLOSED'
                """, Long.class, planId);
        if (count == null || count != 1) conflict("Ce dispatch n'est plus actif ou sa journée est clôturée.");
        ManualDispatchDraft draft = drafts.forPlan(planId).orElseThrow(() -> new FinanceConflictException("Le briefing manuel est introuvable."));
        if (!"FINALIZED".equals(draft.status()) || !planId.equals(draft.finalPlanId())) conflict("Le dispatch a été remplacé.");
        return draft;
    }

    private void cleanup() {
        jdbc.update("""
                UPDATE cash_briefing_batch b SET status = CASE WHEN expires_at <= CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE 'PLAN_INVALID' END
                WHERE b.status = 'ACTIVE' AND (b.expires_at <= CURRENT_TIMESTAMP OR NOT EXISTS (
                    SELECT 1 FROM dispatch_plan p JOIN delivery_operation_day d ON d.dispatch_plan_id = p.id
                    WHERE p.id = b.plan_id AND p.status IN ('READY', 'READY_WITH_WARNINGS')
                        AND p.archived_at IS NULL AND d.status <> 'CLOSED'))
                """);
        jdbc.update("""
                UPDATE cash_ledger_reservation r SET status = 'RELEASED' WHERE r.status = 'ACTIVE' AND EXISTS (
                    SELECT 1 FROM cash_briefing_transfer t JOIN cash_briefing_batch b ON b.id = t.batch_id
                    WHERE t.id = r.proposal_id AND b.status <> 'ACTIVE')
                """);
    }

    private void reserve(UUID transferId, UUID courierId, long amount) {
        jdbc.update("""
                INSERT INTO cash_ledger_reservation (id, proposal_id, account_key, amount_ariary, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, UUID.randomUUID(), transferId, "COURIER:" + courierId, amount);
    }

    private void releaseReservations(UUID batchId) {
        jdbc.update("""
                UPDATE cash_ledger_reservation SET status = 'RELEASED' WHERE status = 'ACTIVE' AND proposal_id IN (
                    SELECT id FROM cash_briefing_transfer WHERE batch_id = ?)
                """, batchId);
    }

    private Batch read(UUID id) {
        List<Transfer> transfers = jdbc.query("SELECT * FROM cash_briefing_transfer WHERE batch_id = ? ORDER BY seller_name, courier_name, id", (rs, n) ->
                new Transfer(rs.getObject("id", UUID.class), rs.getObject("seller_account_id", UUID.class), rs.getString("seller_name"),
                        rs.getString("seller_payout_number"), rs.getObject("courier_account_id", UUID.class), rs.getString("courier_name"),
                        rs.getLong("amount_ariary"), rs.getString("status"), rs.getObject("movement_id", UUID.class)), id);
        List<CashLedgerModels.Account> accounts = ledger.getSnapshot().accounts();
        Map<UUID, String> payoutNumbers = new HashMap<>();
        accounts.stream().filter(a -> "SELLER".equals(a.type())).forEach(a -> payoutNumbers.put(a.entityId(), a.payoutNumber()));
        boolean payoutChanged = transfers.stream().anyMatch(t -> "PENDING".equals(t.status())
                && !Objects.equals(t.sellerPayoutNumber(), payoutNumbers.get(t.sellerAccountId())));
        List<Balance> balances = accounts.stream()
                .filter(a -> transfers.stream().anyMatch(t -> t.courierAccountId().equals(a.entityId())))
                .map(a -> new Balance(a.entityId(), a.name(), a.balanceAriary(), a.availableAriary(), a.reservedAriary())).toList();
        return jdbc.query("SELECT * FROM cash_briefing_batch WHERE id = ?", (rs, n) -> {
            String status = rs.getString("status");
            if ("ACTIVE".equals(status) && (rs.getLong("ledger_revision") != ledger.revision() || payoutChanged)) status = "STALE";
            String message = "STALE".equals(status) ? "Les caisses ou les coordonnées de versement ont changé : revalidez avant de transmettre le briefing."
                    : transfers.isEmpty() ? "Aucun vendeur ne peut être soldé intégralement avec les caisses disponibles et un numéro de versement renseigné."
                    : "Proposition uniquement. Confirmez chaque versement réel dans Finance.";
            return new Batch(id, rs.getObject("plan_id", UUID.class), rs.getObject("service_date", LocalDate.class), status,
                    rs.getLong("ledger_revision"), rs.getTimestamp("expires_at").toInstant(), CashPayoutOptimizer.ALGORITHM,
                    transfers, balances, message);
        }, id).stream().findFirst().orElseThrow(() -> new FinanceConflictException("Cette proposition n'existe plus."));
    }

    private static void conflict(String message) { throw new FinanceConflictException(message); }
}
