package com.azylivraison.finance.cash.briefing;

import static com.azylivraison.finance.cash.briefing.CashBriefingModels.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.azylivraison.account.domain.ClientPayoutDestination;
import com.azylivraison.account.service.PayoutOperatorNormalizer;
import com.azylivraison.dispatch.domain.ManualDispatchDraft;
import com.azylivraison.dispatch.repository.ManualDispatchDraftRepository;
import com.azylivraison.finance.cash.CashLedgerModels;
import com.azylivraison.finance.cash.CashLedgerModels.CashPage;
import com.azylivraison.finance.cash.CashListQuery;
import com.azylivraison.finance.cash.CashLedgerService;
import com.azylivraison.finance.service.FinanceConflictException;
import com.azylivraison.finance.service.FinanceInputException;
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

    /** A page is made of individual transfers, never whole batches or an in-memory slice of history. */
    @Transactional
    public CashPage<BriefingTransferRow> transfers(CashListQuery query) {
        ledger.lock(); cleanup();
        String tables = """
                 FROM (
                    SELECT id, batch_id, status, created_at, 'SELLER_PAYOUT' AS kind,
                        seller_name AS party_name, courier_name AS from_name, '' AS to_name,
                        seller_payout_number AS payout_number, seller_payout_account_holder AS account_holder
                    FROM cash_briefing_transfer
                    UNION ALL
                    SELECT id, batch_id, status, created_at, 'COURIER_TRANSFER' AS kind,
                        '' AS party_name, from_courier_name AS from_name, to_courier_name AS to_name,
                        NULL AS payout_number, NULL AS account_holder
                    FROM cash_briefing_courier_transfer
                ) t JOIN cash_briefing_batch b ON b.id = t.batch_id
                """;
        StringBuilder where = new StringBuilder(" WHERE (t.status = 'CONFIRMED' OR (t.status = 'PENDING' AND b.status = 'ACTIVE'))");
        List<Object> args = new ArrayList<>();
        if (query.from() != null) { where.append(" AND b.service_date >= ?"); args.add(query.from()); }
        if (query.to() != null) { where.append(" AND b.service_date <= ?"); args.add(query.to()); }
        if (query.hasSearch()) {
            where.append("""
                     AND (LOWER(t.party_name) LIKE ? ESCAPE '!' OR LOWER(t.from_name) LIKE ? ESCAPE '!'
                       OR LOWER(t.to_name) LIKE ? ESCAPE '!'
                       OR %s LIKE ? ESCAPE '!'
                       OR LOWER(COALESCE(t.account_holder, '')) LIKE ? ESCAPE '!')
                    """.formatted(CashListQuery.phoneSearchExpression("t.payout_number")));
            args.add(query.searchPattern()); args.add(query.searchPattern());
            args.add(query.searchPattern());
            args.add(query.phoneSearchPattern()); args.add(query.searchPattern());
        }
        long total = jdbc.queryForObject("SELECT COUNT(*)" + tables + where, Long.class, args.toArray());
        args.add(query.size()); args.add(query.offset());
        List<TransferPageEntry> entries = jdbc.query("""
                SELECT t.id, t.batch_id, t.kind
                """ + tables + where + " ORDER BY b.service_date DESC, b.created_at DESC, b.id DESC, t.created_at DESC, t.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new TransferPageEntry(rs.getObject("id", UUID.class),
                        rs.getObject("batch_id", UUID.class), rs.getString("kind")), args.toArray());
        if (entries.isEmpty()) return new CashPage<>(List.of(), total, query);
        // Inspect only batches represented on this SQL page; staleness includes off-page instructions.
        Map<UUID, Batch> batches = new HashMap<>();
        List<BriefingTransferRow> items = entries.stream().map(e -> {
            Batch batch = batches.computeIfAbsent(e.batchId(), this::read);
            Transfer seller = "SELLER_PAYOUT".equals(e.kind())
                    ? batch.transfers().stream().filter(t -> t.id().equals(e.id())).findFirst().orElseThrow() : null;
            CourierTransfer courier = "COURIER_TRANSFER".equals(e.kind())
                    ? batch.courierTransfers().stream().filter(t -> t.id().equals(e.id())).findFirst().orElseThrow() : null;
            return new BriefingTransferRow(batch.id(), batch.serviceDate(), batch.status(), batch.ledgerRevision(),
                    transferMessage(batch.status()), seller, e.kind(), courier);
        }).toList();
        return new CashPage<>(items, total, query);
    }

    private record TransferPageEntry(UUID id, UUID batchId, String kind) {}

    /** Global balances and the current plan only: GET never creates a payout proposal. */
    @Transactional
    public Planning planning(UUID planId) {
        ledger.lock(); cleanup();
        ManualDispatchDraft draft = activeDraft(planId);
        UUID currentId = currentBatchId(planId);
        Batch batch = currentId == null ? null : read(currentId);
        var snapshot = ledger.getSnapshot(false);
        Map<UUID, Long> sellerPlanned = new HashMap<>(), courierPlanned = new HashMap<>();
        Map<UUID, Long> incoming = new HashMap<>(), outgoing = new HashMap<>();
        if (batch != null) for (Transfer transfer : batch.transfers()) {
            if (!"PENDING".equals(transfer.status())) continue;
            sellerPlanned.merge(transfer.sellerAccountId(), transfer.amountAriary(), Math::addExact);
            courierPlanned.merge(transfer.courierAccountId(), transfer.amountAriary(), Math::addExact);
        }
        if (batch != null) for (CourierTransfer transfer : batch.courierTransfers()) {
            if (!"PENDING".equals(transfer.status())) continue;
            incoming.merge(transfer.toCourierAccountId(), transfer.amountAriary(), this::safeTotal);
            outgoing.merge(transfer.fromCourierAccountId(), transfer.amountAriary(), this::safeTotal);
        }
        Map<UUID, Long> elsewhere = sellerReservations(currentId);
        Map<UUID, List<ClientPayoutDestination>> destinations = payoutDestinations();
        List<PlanningSeller> sellers = snapshot.accounts().stream().filter(a -> "SELLER".equals(a.type()))
                .sorted(Comparator.comparing(CashLedgerModels.Account::name, String.CASE_INSENSITIVE_ORDER))
                .map(a -> new PlanningSeller(a.entityId(), a.name(), a.payoutNumber(), a.balanceAriary(),
                        sellerPlanned.getOrDefault(a.entityId(), 0L),
                        elsewhere.getOrDefault(a.entityId(), 0L) == 0 ? Math.max(0L, a.balanceAriary()) : 0,
                        destinations.getOrDefault(a.entityId(), List.of()))).toList();
        Set<UUID> selected = new HashSet<>();
        draft.couriers().forEach(c -> selected.add(c.courierId()));
        Map<UUID, Long> ownReserved = ownReservations(currentId);
        List<PlanningCourier> couriers = snapshot.accounts().stream()
                .filter(a -> "COURIER".equals(a.type()) && selected.contains(a.entityId()))
                .sorted(Comparator.comparing(CashLedgerModels.Account::name, String.CASE_INSENSITIVE_ORDER))
                .map(a -> {
                    long own = ownReserved.getOrDefault(a.entityId(), 0L);
                    long signedCapacity = Math.addExact(a.availableAriary(), own);
                    long capacity = Math.max(0L, signedCapacity);
                    long planned = courierPlanned.getOrDefault(a.entityId(), 0L);
                    long received = incoming.getOrDefault(a.entityId(), 0L), sent = outgoing.getOrDefault(a.entityId(), 0L);
                    return new PlanningCourier(a.entityId(), a.name(), a.balanceAriary(),
                            Math.max(0L, a.reservedAriary() - own), capacity, planned,
                            signedTotal(signedTotal(signedCapacity, received), -safeTotal(sent, planned)), received, sent);
                }).toList();
        return new Planning(planId, draft.serviceDate(), snapshot.revision(), batch, sellers, couriers);
    }

    @Transactional
    public Batch manual(ManualRequest request, UUID actor) {
        if (request == null || request.planId() == null || request.expectedLedgerRevision() == null
                || request.transfers() == null || request.transfers().size() > 1000)
            throw new FinanceInputException("Renseignez les versements à prévoir et la version des caisses.");
        List<ManualTransfer> transfers = normalize(request.transfers());
        List<ManualCourierTransfer> courierTransfers = normalizeCourierTransfers(request.courierTransfers());
        String fingerprint = courierTransfers.isEmpty()
                ? fingerprint("MANUAL", request.planId(), request.expectedLedgerRevision(),
                    request.expectedBatchId(), transfers.stream().map(this::fingerprintTransfer).toList())
                : fingerprint("MANUAL_V2", request.planId(), request.expectedLedgerRevision(),
                    request.expectedBatchId(), transfers.stream().map(this::fingerprintTransfer).toList(), courierTransfers);
        ledger.lock(); cleanup();
        UUID replay = replay(request.idempotencyKey(), actor, request.planId(), "MANUAL", fingerprint);
        if (replay != null) return read(replay);
        validateNewCustomDestinations(transfers);
        ManualDispatchDraft draft = activeDraft(request.planId());
        checkExpected(request.planId(), request.expectedLedgerRevision(), request.expectedBatchId());
        UUID previous = currentBatchId(request.planId());
        if (previous != null) releaseBatch(previous);
        CashLedgerModels.Snapshot snapshot = ledger.getSnapshot();
        Map<UUID, List<ClientPayoutDestination>> destinations = payoutDestinations();
        validateManual(draft, snapshot, transfers, destinations, courierTransfers);
        UUID id = insertBatch(draft, snapshot.revision(), actor, request.idempotencyKey(), "MANUAL", fingerprint);
        Map<UUID, CashLedgerModels.Account> sellers = accounts(snapshot, "SELLER");
        Map<UUID, CashLedgerModels.Account> couriers = accounts(snapshot, "COURIER");
        for (ManualTransfer transfer : transfers) {
            var seller = sellers.get(transfer.sellerAccountId());
            var courier = couriers.get(transfer.courierAccountId());
            insertTransfer(id, seller.entityId(), seller.name(), payoutDestination(transfer, seller, destinations),
                    courier.entityId(), courier.name(), transfer.amountAriary());
        }
        for (ManualCourierTransfer transfer : courierTransfers) {
            var from = couriers.get(transfer.fromCourierAccountId());
            var to = couriers.get(transfer.toCourierAccountId());
            insertCourierTransfer(id, from.entityId(), from.name(), to.entityId(), to.name(), transfer.amountAriary());
        }
        rebuildReservations(id);
        return read(id);
    }

    @Transactional
    public Batch prepare(PrepareRequest request, UUID actor) {
        ledger.lock(); cleanup();
        String hash = request.replaceExisting() ? fingerprint("AUTO", request.planId(), request.expectedLedgerRevision(),
                request.expectedBatchId(), true) : null;
        UUID replayId = replay(request.idempotencyKey(), actor, request.planId(), "AUTO", hash);
        if (replayId != null) return read(replayId);
        ManualDispatchDraft draft = activeDraft(request.planId());
        UUID existing = currentBatchId(request.planId());
        if (request.replaceExisting()) {
            checkExpected(request.planId(), request.expectedLedgerRevision(), request.expectedBatchId());
            if (existing != null) releaseBatch(existing);
        } else if (existing != null) return read(existing);
        CashLedgerModels.Snapshot snapshot = ledger.getSnapshot();
        List<CashPayoutOptimizer.Courier> couriers = couriers(draft, snapshot);
        Set<UUID> reservedSellers = new HashSet<>(jdbc.query("""
                SELECT DISTINCT t.seller_account_id FROM cash_briefing_transfer t
                JOIN cash_briefing_batch b ON b.id = t.batch_id
                WHERE b.status = 'ACTIVE' AND t.status = 'PENDING'
                """, (rs, n) -> rs.getObject(1, UUID.class)));
        Map<UUID, LocalDate> oldest = new HashMap<>();
        Map<UUID, List<ClientPayoutDestination>> destinations = payoutDestinations();
        snapshot.obligations().stream().filter(o -> o.active() && o.remainingAriary() > 0)
                .forEach(o -> oldest.merge(o.sellerAccountId(), o.serviceDate(), (a, b) -> a.isBefore(b) ? a : b));
        List<CashPayoutOptimizer.Seller> sellers = snapshot.accounts().stream()
                .filter(a -> "SELLER".equals(a.type()) && a.balanceAriary() > 0 && !reservedSellers.contains(a.entityId()))
                .filter(a -> firstNewDestination(destinations.get(a.entityId())) != null)
                .map(a -> new CashPayoutOptimizer.Seller(a.entityId(), a.name(),
                        firstNewDestination(destinations.get(a.entityId())).number(), a.balanceAriary(),
                        oldest.getOrDefault(a.entityId(), LocalDate.MAX))).toList();
        var allocationPlan = optimizer.optimizePlan(sellers, couriers);
        UUID id = insertBatch(draft, snapshot.revision(), actor, request.idempotencyKey(), "AUTO", hash);
        for (var allocation : allocationPlan.payouts()) {
            insertTransfer(id, allocation.seller().id(), allocation.seller().name(),
                    snapshot(firstNewDestination(destinations.get(allocation.seller().id()))),
                    allocation.courier().id(), allocation.courier().name(), allocation.amount());
        }
        for (var transfer : allocationPlan.courierTransfers()) {
            insertCourierTransfer(id, transfer.from().id(), transfer.from().name(), transfer.to().id(),
                    transfer.to().name(), transfer.amount());
        }
        rebuildReservations(id);
        return read(id);
    }

    @Transactional
    public Batch release(UUID id) {
        ledger.lock(); cleanup();
        Batch batch = read(id);
        if ("ACTIVE".equals(batch.status()) || "STALE".equals(batch.status())) {
            releaseBatch(id);
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
        Map<UUID, List<ClientPayoutDestination>> destinations = payoutDestinations();
        Map<UUID, Long> promised = new HashMap<>();
        for (Transfer transfer : batch.transfers()) {
            if (!"PENDING".equals(transfer.status())) continue;
            if (batch.workflowVersion() == 1) {
                long left = available.getOrDefault(transfer.courierAccountId(), 0L) - transfer.amountAriary();
                if (left < 0) conflict("La caisse d'un livreur ne couvre plus les versements. Libérez puis préparez une nouvelle proposition.");
                available.put(transfer.courierAccountId(), left);
            }
            promised.merge(transfer.sellerAccountId(), transfer.amountAriary(), Math::addExact);
        }
        Map<UUID, Long> reservedSellers = sellerReservations(id);
        for (var entry : promised.entrySet()) {
            long debt = sellerDebt.getOrDefault(entry.getKey(), 0L);
            long elsewhere = reservedSellers.getOrDefault(entry.getKey(), 0L);
            boolean valid = batch.workflowVersion() == 1 && "MANUAL".equals(batch.mode())
                    ? entry.getValue() <= Math.max(0L, debt - elsewhere)
                    : entry.getValue() == debt && elsewhere == 0;
            if (!valid)
                conflict("Le solde d'un vendeur a changé. Libérez puis préparez une nouvelle proposition.");
        }
        for (Transfer transfer : batch.transfers()) if ("PENDING".equals(transfer.status())) {
            if (transfer.sellerPayoutNumberOverride()) {
                if (!validPayoutNumber(transfer.sellerPayoutNumber()) || !validAccountHolder(transfer.sellerPayoutAccountHolder(), 200))
                    conflict("Renseignez un numéro et un titulaire de versement valides avant de revalider.");
            } else {
                ClientPayoutDestination destination = linkedDestination(transfer, destinations);
                if (!validDestination(destination))
                    conflict("La destination de versement choisie n'est plus active. Modifiez le versement avant de revalider.");
                jdbc.update("""
                        UPDATE cash_briefing_transfer SET seller_payout_number = ?, seller_payout_destination_id = ?,
                            seller_payout_account_holder = ?, seller_payout_operator = ? WHERE id = ?
                        """, destination.number(), destination.id(), destination.accountHolder(),
                        snapshotOperator(destination), transfer.id());
            }
            if (batch.workflowVersion() == 1) reserve(transfer.id(), transfer.courierAccountId(), transfer.amountAriary());
        }
        if (batch.workflowVersion() == 2) rebuildReservations(id);
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
        if ("CONFIRMED".equals(transfer.status()) || "VOIDED".equals(transfer.status())) return batch;
        if (!"ACTIVE".equals(batch.status()) || request.expectedLedgerRevision() == null || request.expectedLedgerRevision() != ledger.revision())
            conflict("Les caisses ont changé. Revalidez la proposition avant de confirmer un versement.");
        activeDraft(batch.planId());
        if (!"PENDING".equals(transfer.status())) conflict("Ce versement n'est plus proposé.");
        if (!validPayoutNumber(transfer.sellerPayoutNumber()) || !validAccountHolder(transfer.sellerPayoutAccountHolder(), 200))
            conflict("Renseignez un numéro et un titulaire de versement valides avant de confirmer.");
        if (batch.workflowVersion() == 2) {
            if (!transfer.readyToConfirm()) conflict(transfer.blockedReason());
        } else {
            int released = jdbc.update("UPDATE cash_ledger_reservation SET status = 'CONFIRMED' WHERE proposal_id = ? AND status = 'ACTIVE'", transferId);
            if (released != 1) conflict("La réservation n'est plus valide. Revalidez la proposition.");
        }
        var movement = ledger.confirmTransferInternal(new CashLedgerModels.TransferRequest("briefing-transfer:" + transferId,
                "COURIER:" + transfer.courierAccountId(), "SELLER:" + transfer.sellerAccountId(), transfer.amountAriary(),
                request.paymentMethod(), request.reference(), request.note(), null), actor, proof);
        jdbc.update("UPDATE cash_briefing_transfer SET status = 'CONFIRMED', movement_id = ? WHERE id = ?", movement.id(), transferId);
        if (batch.workflowVersion() == 2) rebuildReservations(batchId);
        // This known, reserved transfer cannot invalidate the other allocations' budgets.
        jdbc.update("UPDATE cash_briefing_batch SET ledger_revision = ? WHERE status = 'ACTIVE' AND ledger_revision = ?",
                ledger.revision(), batch.ledgerRevision());
        completeIfFinished(batchId);
        return read(batchId);
    }

    @Transactional
    public Batch confirmCourierTransfer(UUID transferId, ConfirmRequest request, UUID actor, MultipartFile proof) {
        ledger.lock(); cleanup();
        UUID batchId = jdbc.query("SELECT batch_id FROM cash_briefing_courier_transfer WHERE id = ?",
                (rs, n) -> rs.getObject(1, UUID.class), transferId).stream().findFirst()
                .orElseThrow(() -> new FinanceConflictException("Ce transfert entre livreurs n'existe plus."));
        Batch batch = read(batchId);
        CourierTransfer transfer = batch.courierTransfers().stream().filter(t -> t.id().equals(transferId)).findFirst().orElseThrow();
        if ("CONFIRMED".equals(transfer.status()) || "VOIDED".equals(transfer.status())) return batch;
        if (!"ACTIVE".equals(batch.status()) || request.expectedLedgerRevision() == null
                || request.expectedLedgerRevision() != ledger.revision())
            conflict("Les caisses ont changé. Revalidez la préparation avant de confirmer un transfert.");
        activeDraft(batch.planId());
        if (!"PENDING".equals(transfer.status())) conflict("Ce transfert n'est plus prévu.");
        if (!transfer.readyToConfirm()) conflict(transfer.blockedReason());
        var movement = ledger.confirmTransferInternal(new CashLedgerModels.TransferRequest("briefing-courier-transfer:" + transferId,
                "COURIER:" + transfer.fromCourierAccountId(), "COURIER:" + transfer.toCourierAccountId(), transfer.amountAriary(),
                request.paymentMethod(), request.reference(), request.note(), null), actor, proof);
        jdbc.update("UPDATE cash_briefing_courier_transfer SET status = 'CONFIRMED', movement_id = ? WHERE id = ?", movement.id(), transferId);
        rebuildReservations(batchId);
        jdbc.update("UPDATE cash_briefing_batch SET ledger_revision = ? WHERE status = 'ACTIVE' AND ledger_revision = ?",
                ledger.revision(), batch.ledgerRevision());
        completeIfFinished(batchId);
        return read(batchId);
    }

    private void completeIfFinished(UUID batchId) {
        long pending = jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_transfer WHERE batch_id = ? AND status = 'PENDING'", Long.class, batchId)
                + jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_courier_transfer WHERE batch_id = ? AND status = 'PENDING'", Long.class, batchId);
        if (pending == 0) jdbc.update("UPDATE cash_briefing_batch SET status = 'COMPLETED' WHERE id = ?", batchId);
    }

    private List<CashPayoutOptimizer.Courier> couriers(ManualDispatchDraft draft, CashLedgerModels.Snapshot snapshot) {
        Set<UUID> ids = new HashSet<>();
        draft.couriers().forEach(c -> ids.add(c.courierId()));
        return snapshot.accounts().stream().filter(a -> "COURIER".equals(a.type()) && ids.contains(a.entityId()))
                .filter(a -> a.availableAriary() >= 0)
                .map(a -> new CashPayoutOptimizer.Courier(a.entityId(), a.name(), a.availableAriary())).toList();
    }

    private Map<UUID, CashLedgerModels.Account> accounts(CashLedgerModels.Snapshot snapshot, String type) {
        Map<UUID, CashLedgerModels.Account> result = new HashMap<>();
        snapshot.accounts().stream().filter(a -> type.equals(a.type())).forEach(a -> result.put(a.entityId(), a));
        return result;
    }

    private Map<UUID, Long> sellerReservations(UUID excludedBatch) {
        Map<UUID, Long> result = new HashMap<>();
        jdbc.query("""
                SELECT t.seller_account_id, t.amount_ariary FROM cash_briefing_transfer t
                JOIN cash_briefing_batch b ON b.id = t.batch_id
                WHERE b.status = 'ACTIVE' AND t.status = 'PENDING'
                """ + (excludedBatch == null ? "" : " AND b.id <> ?"), rs -> {
            result.merge(rs.getObject(1, UUID.class), rs.getLong(2), Math::addExact);
        }, excludedBatch == null ? new Object[] {} : new Object[] {excludedBatch});
        return result;
    }

    private Map<UUID, Long> ownReservations(UUID batchId) {
        Map<UUID, Long> result = new HashMap<>();
        if (batchId == null) return result;
        jdbc.query("""
                SELECT r.account_key, r.amount_ariary FROM cash_ledger_reservation r
                WHERE r.status = 'ACTIVE' AND (r.proposal_id = ? OR r.proposal_id IN (
                    SELECT id FROM cash_briefing_transfer WHERE batch_id = ?))
                """, rs -> { result.merge(UUID.fromString(rs.getString(1).substring("COURIER:".length())),
                        rs.getLong(2), Math::addExact); }, batchId, batchId);
        return result;
    }

    private void validateManual(ManualDispatchDraft draft, CashLedgerModels.Snapshot snapshot,
            List<ManualTransfer> transfers, Map<UUID, List<ClientPayoutDestination>> destinations,
            List<ManualCourierTransfer> courierTransfers) {
        Map<UUID, CashLedgerModels.Account> sellers = accounts(snapshot, "SELLER");
        Map<UUID, CashLedgerModels.Account> couriers = accounts(snapshot, "COURIER");
        Set<UUID> selected = new HashSet<>();
        draft.couriers().forEach(c -> selected.add(c.courierId()));
        Map<UUID, Long> sellerTotals = new HashMap<>();
        for (ManualTransfer transfer : transfers) {
            var seller = sellers.get(transfer.sellerAccountId());
            var courier = couriers.get(transfer.courierAccountId());
            if (seller == null) conflict("Ce vendeur n'existe plus.");
            payoutDestination(transfer, seller, destinations);
            if (courier == null || !selected.contains(transfer.courierAccountId()))
                conflict("Choisissez un livreur actif dans ce dispatch.");
            if (sellerTotals.putIfAbsent(seller.entityId(), transfer.amountAriary()) != null)
                conflict("Prévoyez un seul versement et un seul livreur par vendeur.");
        }
        Map<UUID, Long> reserved = sellerReservations(null);
        for (var entry : sellerTotals.entrySet()) {
            long available = Math.max(0L, sellers.get(entry.getKey()).balanceAriary());
            if (reserved.getOrDefault(entry.getKey(), 0L) != 0)
                conflict("Un versement est déjà prévu pour ce vendeur dans une autre préparation.");
            if (entry.getValue() != available)
                conflict("Le versement doit régler intégralement le solde disponible du vendeur.");
        }
        validateCashPlan(selected, couriers, transfers, courierTransfers);
    }

    private List<ManualCourierTransfer> normalizeCourierTransfers(List<ManualCourierTransfer> input) {
        if (input == null) return List.of();
        if (input.size() > 1000) throw new FinanceInputException("Trop de transferts entre livreurs.");
        record Pair(UUID from, UUID to) {}
        Map<Pair, Long> totals = new HashMap<>();
        for (ManualCourierTransfer transfer : input) {
            if (transfer == null || transfer.fromCourierAccountId() == null || transfer.toCourierAccountId() == null
                    || transfer.fromCourierAccountId().equals(transfer.toCourierAccountId())
                    || transfer.amountAriary() <= 0 || transfer.amountAriary() > CashLedgerService.MAX_SAFE_ARIARY)
                throw new FinanceInputException("Chaque transfert doit relier deux livreurs différents avec un montant entier positif valide.");
            totals.merge(new Pair(transfer.fromCourierAccountId(), transfer.toCourierAccountId()), transfer.amountAriary(), this::safeTotal);
        }
        return totals.entrySet().stream().map(e -> new ManualCourierTransfer(e.getKey().from(), e.getKey().to(), e.getValue()))
                .sorted(Comparator.comparing((ManualCourierTransfer t) -> t.fromCourierAccountId().toString())
                        .thenComparing(t -> t.toCourierAccountId().toString())).toList();
    }

    /** Every incoming amount remains hypothetical here; no ledger leg or reservation is credited to its recipient. */
    private void validateCashPlan(Set<UUID> selected, Map<UUID, CashLedgerModels.Account> couriers,
            List<ManualTransfer> payouts, List<ManualCourierTransfer> funding) {
        Map<UUID, Long> cash = new HashMap<>();
        couriers.forEach((id, account) -> cash.put(id, account.availableAriary()));
        Map<UUID, List<ManualCourierTransfer>> outgoing = new HashMap<>();
        Map<UUID, Integer> indegree = new HashMap<>();
        for (ManualCourierTransfer transfer : funding) {
            if (!selected.contains(transfer.fromCourierAccountId()) || !selected.contains(transfer.toCourierAccountId())
                    || !couriers.containsKey(transfer.fromCourierAccountId()) || !couriers.containsKey(transfer.toCourierAccountId()))
                conflict("Choisissez des livreurs actifs dans ce dispatch pour les transferts.");
            outgoing.computeIfAbsent(transfer.fromCourierAccountId(), ignored -> new ArrayList<>()).add(transfer);
            indegree.putIfAbsent(transfer.fromCourierAccountId(), 0);
            indegree.merge(transfer.toCourierAccountId(), 1, Integer::sum);
        }
        var ready = new java.util.PriorityQueue<UUID>(Comparator.comparing(UUID::toString));
        indegree.forEach((id, degree) -> { if (degree == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            UUID from = ready.remove(); visited++;
            List<ManualCourierTransfer> sends = outgoing.getOrDefault(from, List.of());
            long total = sends.stream().mapToLong(ManualCourierTransfer::amountAriary).reduce(0, this::safeTotal);
            if (total > 0 && cash.getOrDefault(from, 0L) < total)
                conflict("Les caisses réelles ne permettent pas d'effectuer les transferts prévus entre livreurs.");
            cash.put(from, Math.subtractExact(cash.getOrDefault(from, 0L), total));
            for (ManualCourierTransfer transfer : sends) {
                cash.put(transfer.toCourierAccountId(), signedTotal(cash.getOrDefault(transfer.toCourierAccountId(), 0L), transfer.amountAriary()));
                if (indegree.merge(transfer.toCourierAccountId(), -1, Integer::sum) == 0) ready.add(transfer.toCourierAccountId());
            }
        }
        if (visited != indegree.size()) conflict("Les transferts entre livreurs ne peuvent pas former une boucle.");
        for (ManualTransfer payout : payouts) {
            if (!selected.contains(payout.courierAccountId()) || !couriers.containsKey(payout.courierAccountId()))
                conflict("Choisissez un livreur actif dans ce dispatch.");
            long available = cash.getOrDefault(payout.courierAccountId(), 0L);
            if (payout.amountAriary() > available)
                conflict("La caisse disponible et les apports prévus ne couvrent pas les versements du livreur.");
            cash.put(payout.courierAccountId(), available - payout.amountAriary());
        }
    }

    private long signedTotal(long left, long right) {
        try {
            long total = Math.addExact(left, right);
            if (total > CashLedgerService.MAX_SAFE_ARIARY || total < -CashLedgerService.MAX_SAFE_ARIARY)
                throw new ArithmeticException("Amount too large");
            return total;
        } catch (ArithmeticException exception) { throw new FinanceInputException("Le montant total des versements est trop élevé."); }
    }

    /**
     * New preparations reserve original cash per courier, not expected receipts. For an acyclic cash plan,
     * initial backing is max(0, remaining outgoing - remaining incoming). Confirming a handover credits real
     * cash and moves its backing to the recipient in the same locked transaction.
     */
    private void rebuildReservations(UUID batchId) {
        releaseReservations(batchId);
        Batch batch = read(batchId);
        ManualDispatchDraft draft = activeDraft(batch.planId());
        Set<UUID> selected = new HashSet<>();
        draft.couriers().forEach(c -> selected.add(c.courierId()));
        var snapshot = ledger.getSnapshot(false);
        List<ManualTransfer> payouts = batch.transfers().stream().filter(t -> "PENDING".equals(t.status()))
                .map(t -> new ManualTransfer(t.sellerAccountId(), t.courierAccountId(), t.amountAriary())).toList();
        List<ManualCourierTransfer> funding = batch.courierTransfers().stream().filter(t -> "PENDING".equals(t.status()))
                .map(t -> new ManualCourierTransfer(t.fromCourierAccountId(), t.toCourierAccountId(), t.amountAriary())).toList();
        validateCashPlan(selected, accounts(snapshot, "COURIER"), payouts, funding);
        Map<UUID, Long> net = new HashMap<>();
        payouts.forEach(t -> net.merge(t.courierAccountId(), t.amountAriary(), this::signedTotal));
        funding.forEach(t -> {
            net.merge(t.fromCourierAccountId(), t.amountAriary(), this::signedTotal);
            net.merge(t.toCourierAccountId(), -t.amountAriary(), this::signedTotal);
        });
        net.forEach((courier, amount) -> { if (amount > 0) reserve(batchId, courier, amount); });
    }

    private List<ManualTransfer> normalize(List<ManualTransfer> input) {
        record Pair(UUID seller, UUID courier, String payoutNumber, UUID destinationId,
                String accountHolder, String operator) {}
        Map<Pair, Long> totals = new HashMap<>();
        Map<UUID, Long> sellerTotals = new HashMap<>(), courierTotals = new HashMap<>();
        for (ManualTransfer transfer : input) {
            if (transfer == null || transfer.sellerAccountId() == null || transfer.courierAccountId() == null
                    || transfer.amountAriary() <= 0 || transfer.amountAriary() > CashLedgerService.MAX_SAFE_ARIARY)
                throw new FinanceInputException("Chaque versement doit être un montant entier positif valide.");
            String number = transfer.sellerPayoutNumber();
            String holder = transfer.sellerPayoutAccountHolder();
            String operator = transfer.sellerPayoutOperator();
            if (transfer.sellerPayoutDestinationId() != null && (number != null || holder != null || operator != null))
                throw new FinanceInputException("Choisissez un numéro enregistré ou des coordonnées personnalisées, pas les deux.");
            if (number != null) {
                if (!validPayoutNumber(number))
                    throw new FinanceInputException("Renseignez un numéro de versement valide (100 caractères maximum).");
                number = number.trim();
            }
            if (holder != null) {
                if (number == null || !validAccountHolder(holder, 150))
                    throw new FinanceInputException("Renseignez le numéro et son titulaire (150 caractères maximum).");
                holder = holder.trim();
            }
            if (operator != null) {
                if (number == null)
                    throw new FinanceInputException("Renseignez un numéro personnalisé avec son opérateur.");
                operator = PayoutOperatorNormalizer.normalize(operator);
            }
            Pair key = new Pair(transfer.sellerAccountId(), transfer.courierAccountId(), number,
                    transfer.sellerPayoutDestinationId(), holder, operator);
            long amount = safeTotal(totals.getOrDefault(key, 0L), transfer.amountAriary());
            totals.put(key, amount);
            sellerTotals.merge(transfer.sellerAccountId(), transfer.amountAriary(), this::safeTotal);
            courierTotals.merge(transfer.courierAccountId(), transfer.amountAriary(), this::safeTotal);
        }
        return totals.entrySet().stream().map(e -> new ManualTransfer(e.getKey().seller(), e.getKey().courier(),
                        e.getValue(), e.getKey().payoutNumber(), e.getKey().destinationId(),
                        e.getKey().accountHolder(), e.getKey().operator()))
                .sorted(Comparator.comparing((ManualTransfer t) -> t.sellerAccountId().toString())
                        .thenComparing(t -> t.courierAccountId().toString())
                        .thenComparing(ManualTransfer::sellerPayoutNumber, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(t -> t.sellerPayoutDestinationId() == null ? null : t.sellerPayoutDestinationId().toString(),
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ManualTransfer::sellerPayoutAccountHolder, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ManualTransfer::sellerPayoutOperator, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }

    /**
     * Validate after idempotency lookup so retries of pre-operator V26/V27 requests can still
     * retrieve their existing batch. A genuinely new custom destination must identify a
     * supported mobile-money operator.
     */
    private void validateNewCustomDestinations(List<ManualTransfer> transfers) {
        for (ManualTransfer transfer : transfers) {
            if (transfer.sellerPayoutNumber() == null) continue;
            if (!validAccountHolder(transfer.sellerPayoutAccountHolder(), 150))
                throw new FinanceInputException("Renseignez le titulaire du numéro de versement personnalisé.");
            PayoutOperatorNormalizer.normalize(transfer.sellerPayoutOperator());
        }
    }

    private record PayoutSnapshot(UUID destinationId, String number, String accountHolder, String operator, boolean custom) {}

    private PayoutSnapshot payoutDestination(ManualTransfer transfer, CashLedgerModels.Account seller,
            Map<UUID, List<ClientPayoutDestination>> destinations) {
        if (transfer.sellerPayoutNumber() != null) {
            // This check deliberately follows the idempotent replay lookup: a V26 retry may omit the new holder.
            if (!validAccountHolder(transfer.sellerPayoutAccountHolder(), 150))
                throw new FinanceInputException("Renseignez le titulaire du numéro de versement personnalisé.");
            return new PayoutSnapshot(null, transfer.sellerPayoutNumber(), transfer.sellerPayoutAccountHolder(),
                    PayoutOperatorNormalizer.normalize(transfer.sellerPayoutOperator()), true);
        }
        List<ClientPayoutDestination> choices = destinations.getOrDefault(seller.entityId(), List.of());
        ClientPayoutDestination destination = transfer.sellerPayoutDestinationId() == null ? firstNewDestination(choices)
                : choices.stream().filter(d -> transfer.sellerPayoutDestinationId().equals(d.id())).findFirst().orElse(null);
        if (!validNewDestination(destination))
            conflict("Choisissez une destination de versement active du vendeur ou renseignez des coordonnées personnalisées.");
        return snapshot(destination);
    }

    private Map<UUID, List<ClientPayoutDestination>> payoutDestinations() {
        Map<UUID, List<ClientPayoutDestination>> result = new HashMap<>();
        jdbc.query("""
                SELECT id, account_id, operator, account_holder, number, priority, active
                FROM client_payout_destination WHERE active = TRUE ORDER BY account_id, priority, id
                """, rs -> {
            UUID seller = rs.getObject("account_id", UUID.class);
            result.computeIfAbsent(seller, ignored -> new ArrayList<>()).add(new ClientPayoutDestination(
                    rs.getObject("id", UUID.class), rs.getString("operator"), rs.getString("account_holder"),
                    rs.getString("number"), rs.getInt("priority"), rs.getBoolean("active")));
        });
        return result;
    }

    private static ClientPayoutDestination firstDestination(List<ClientPayoutDestination> choices) {
        return choices == null || choices.isEmpty() ? null : choices.getFirst();
    }

    private static ClientPayoutDestination firstNewDestination(List<ClientPayoutDestination> choices) {
        return choices == null ? null : choices.stream().filter(CashBriefingService::validNewDestination).findFirst().orElse(null);
    }

    private static boolean validNewDestination(ClientPayoutDestination destination) {
        return validDestination(destination)
                && PayoutOperatorNormalizer.supportedName(destination.operator()).isPresent();
    }

    private ClientPayoutDestination linkedDestination(Transfer transfer,
            Map<UUID, List<ClientPayoutDestination>> destinations) {
        List<ClientPayoutDestination> choices = destinations.getOrDefault(transfer.sellerAccountId(), List.of());
        return transfer.sellerPayoutDestinationId() == null ? firstDestination(choices)
                : choices.stream().filter(d -> transfer.sellerPayoutDestinationId().equals(d.id())).findFirst().orElse(null);
    }

    private static PayoutSnapshot snapshot(ClientPayoutDestination destination) {
        return new PayoutSnapshot(destination.id(), destination.number(), destination.accountHolder(),
                snapshotOperator(destination), false);
    }

    private static String snapshotOperator(ClientPayoutDestination destination) {
        return PayoutOperatorNormalizer.supportedName(destination.operator()).orElse(destination.operator());
    }

    private static boolean validDestination(ClientPayoutDestination destination) {
        return destination != null && destination.active() && validPayoutNumber(destination.number())
                && validAccountHolder(destination.accountHolder(), 150) && validAccountHolder(destination.operator(), 80);
    }

    private static boolean validAccountHolder(String holder, int maxLength) {
        return holder != null && !holder.isBlank() && holder.length() <= maxLength
                && holder.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean validPayoutNumber(String number) {
        return number != null && !number.isBlank() && number.length() <= 100
                && number.codePoints().noneMatch(Character::isISOControl);
    }

    /** Keep old idempotency fingerprints stable when callers omit the new optional number. */
    private String fingerprintTransfer(ManualTransfer transfer) {
        if (transfer.sellerPayoutOperator() != null)
            return "ManualTransferV28[" + transfer.sellerAccountId() + "|" + transfer.courierAccountId() + "|"
                    + transfer.amountAriary() + "|" + fingerprintField(transfer.sellerPayoutNumber())
                    + fingerprintField(Objects.toString(transfer.sellerPayoutDestinationId(), null))
                    + fingerprintField(transfer.sellerPayoutAccountHolder())
                    + fingerprintField(transfer.sellerPayoutOperator()) + "]";
        if (transfer.sellerPayoutDestinationId() != null || transfer.sellerPayoutAccountHolder() != null)
            return "ManualTransferV27[" + transfer.sellerAccountId() + "|" + transfer.courierAccountId() + "|"
                    + transfer.amountAriary() + "|" + fingerprintField(transfer.sellerPayoutNumber())
                    + fingerprintField(Objects.toString(transfer.sellerPayoutDestinationId(), null))
                    + fingerprintField(transfer.sellerPayoutAccountHolder()) + "]";
        String oldPrefix = "ManualTransfer[sellerAccountId=" + transfer.sellerAccountId() + ", courierAccountId="
                + transfer.courierAccountId() + ", amountAriary=" + transfer.amountAriary();
        return oldPrefix + (transfer.sellerPayoutNumber() == null ? "" : ", sellerPayoutNumber=" + transfer.sellerPayoutNumber()) + "]";
    }

    private static String fingerprintField(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private long safeTotal(long left, long right) {
        try {
            long total = Math.addExact(left, right);
            if (total > CashLedgerService.MAX_SAFE_ARIARY) throw new ArithmeticException("Amount too large");
            return total;
        } catch (ArithmeticException exception) {
            throw new FinanceInputException("Le montant total des versements est trop élevé.");
        }
    }

    private UUID currentBatchId(UUID planId) {
        return jdbc.query("SELECT id FROM cash_briefing_batch WHERE plan_id = ? AND status = 'ACTIVE'",
                (rs, n) -> rs.getObject(1, UUID.class), planId).stream().findFirst().orElse(null);
    }

    private void checkExpected(UUID planId, Long expectedRevision, UUID expectedBatch) {
        if (expectedRevision == null || expectedRevision != ledger.revision())
            conflict("Les caisses ont changé. Actualisez avant d'enregistrer les versements prévus.");
        if (!Objects.equals(currentBatchId(planId), expectedBatch))
            conflict("Les versements prévus ont été modifiés. Actualisez avant de continuer.");
    }

    private UUID replay(String key, UUID actor, UUID planId, String mode, String fingerprint) {
        if (key == null || key.isBlank() || key.length() > 100)
            throw new FinanceInputException("La clé d'enregistrement des versements est invalide.");
        return jdbc.query("""
                SELECT id, plan_id, planning_mode, request_fingerprint FROM cash_briefing_batch
                WHERE actor_account_id = ? AND idempotency_key = ?
                """, (rs, n) -> {
            if (!planId.equals(rs.getObject("plan_id", UUID.class)) || !mode.equals(rs.getString("planning_mode"))
                    || !Objects.equals(fingerprint, rs.getString("request_fingerprint")))
                conflict("Cette clé d'enregistrement a déjà été utilisée pour d'autres versements.");
            return rs.getObject("id", UUID.class);
        }, actor, key).stream().findFirst().orElse(null);
    }

    private static String fingerprint(Object... values) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(java.util.Arrays.toString(values).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private void releaseBatch(UUID id) {
        jdbc.update("UPDATE cash_briefing_batch SET status = 'RELEASED' WHERE id = ?", id);
        releaseReservations(id);
    }

    private UUID insertBatch(ManualDispatchDraft draft, long revision, UUID actor, String key, String mode, String fingerprint) {
        UUID id = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of("Indian/Antananarivo"));
        Instant expires = (draft.serviceDate().isBefore(today) ? today : draft.serviceDate())
                .plusDays(1).atStartOfDay(ZoneId.of("Indian/Antananarivo")).toInstant();
        jdbc.update("""
                INSERT INTO cash_briefing_batch (id, plan_id, service_date, ledger_revision, status,
                    actor_account_id, idempotency_key, expires_at, planning_mode, request_fingerprint, workflow_version)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, 2)
                """, id, draft.finalPlanId(), draft.serviceDate(), revision, actor, key,
                java.sql.Timestamp.from(expires), mode, fingerprint);
        return id;
    }

    private void insertTransfer(UUID batchId, UUID sellerId, String sellerName, PayoutSnapshot destination,
            UUID courierId, String courierName, long amount) {
        UUID transferId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cash_briefing_transfer (id, batch_id, seller_account_id, seller_name,
                    seller_payout_number, courier_account_id, courier_name, amount_ariary, status, seller_payout_number_override,
                    seller_payout_destination_id, seller_payout_account_holder, seller_payout_operator)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                """, transferId, batchId, sellerId, sellerName, destination.number(), courierId, courierName, amount,
                destination.custom(), destination.destinationId(), destination.accountHolder(), destination.operator());
    }

    private void insertCourierTransfer(UUID batchId, UUID fromId, String fromName, UUID toId, String toName, long amount) {
        jdbc.update("""
                INSERT INTO cash_briefing_courier_transfer (id, batch_id, from_courier_account_id,
                    from_courier_name, to_courier_account_id, to_courier_name, amount_ariary, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """, UUID.randomUUID(), batchId, fromId, fromName, toId, toName, amount);
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
                    SELECT 1 FROM cash_briefing_batch b WHERE b.status <> 'ACTIVE' AND (
                        b.id = r.proposal_id OR EXISTS (SELECT 1 FROM cash_briefing_transfer t
                            WHERE t.batch_id = b.id AND t.id = r.proposal_id)))
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
                UPDATE cash_ledger_reservation SET status = 'RELEASED' WHERE status = 'ACTIVE'
                    AND (proposal_id = ? OR proposal_id IN (SELECT id FROM cash_briefing_transfer WHERE batch_id = ?))
                """, batchId, batchId);
    }

    private Batch read(UUID id) {
        List<Transfer> transfers = jdbc.query("SELECT * FROM cash_briefing_transfer WHERE batch_id = ? ORDER BY seller_name, courier_name, id",
                (rs, n) -> transfer(rs), id);
        List<CourierTransfer> courierTransfers = jdbc.query("SELECT * FROM cash_briefing_courier_transfer WHERE batch_id = ? ORDER BY from_courier_name, to_courier_name, id",
                (rs, n) -> new CourierTransfer(rs.getObject("id", UUID.class), rs.getObject("from_courier_account_id", UUID.class),
                        rs.getString("from_courier_name"), rs.getObject("to_courier_account_id", UUID.class), rs.getString("to_courier_name"),
                        rs.getLong("amount_ariary"), rs.getString("status"), rs.getObject("movement_id", UUID.class), false, null), id);
        List<CashLedgerModels.Account> accounts = ledger.getSnapshot(false).accounts();
        Map<UUID, Long> capacities = new HashMap<>();
        Map<UUID, Long> own = ownReservations(id);
        accounts.stream().filter(a -> "COURIER".equals(a.type())).forEach(a -> capacities.put(a.entityId(),
                signedTotal(a.availableAriary(), own.getOrDefault(a.entityId(), 0L))));
        Set<UUID> reversed = new HashSet<>(jdbc.query("""
                SELECT reversal_of FROM cash_ledger_movement WHERE reversal_of IS NOT NULL AND reversal_of IN (
                    SELECT movement_id FROM cash_briefing_transfer WHERE batch_id = ?
                    UNION ALL SELECT movement_id FROM cash_briefing_courier_transfer WHERE batch_id = ?)
                """, (rs, n) -> rs.getObject(1, UUID.class), id, id));
        Map<UUID, List<ClientPayoutDestination>> destinations = payoutDestinations();
        boolean payoutChanged = transfers.stream().anyMatch(t -> payoutChanged(t, destinations));
        List<Balance> balances = accounts.stream()
                .filter(a -> "COURIER".equals(a.type()) && (transfers.stream().anyMatch(t -> t.courierAccountId().equals(a.entityId()))
                        || courierTransfers.stream().anyMatch(t -> t.fromCourierAccountId().equals(a.entityId()) || t.toCourierAccountId().equals(a.entityId()))))
                .map(a -> new Balance(a.entityId(), a.name(), a.balanceAriary(), a.availableAriary(), a.reservedAriary())).toList();
        return jdbc.query("SELECT * FROM cash_briefing_batch WHERE id = ?", (rs, n) -> {
            String status = rs.getString("status");
            if ("ACTIVE".equals(status) && (rs.getLong("ledger_revision") != ledger.revision() || payoutChanged)) status = "STALE";
            String mode = rs.getString("planning_mode");
            int workflowVersion = rs.getInt("workflow_version");
            String message = "STALE".equals(status) ? "Les caisses ou les coordonnées de versement ont changé : revalidez avant de transmettre le briefing."
                    : transfers.isEmpty() && courierTransfers.isEmpty() && "MANUAL".equals(mode) ? "Aucun versement prévu."
                    : transfers.isEmpty() && courierTransfers.isEmpty() ? "Aucun vendeur ne peut être soldé intégralement avec les caisses disponibles et un numéro de versement renseigné."
                    : "Proposition uniquement. Confirmez chaque versement réel dans Finance.";
            String batchStatus = status;
            List<Transfer> decoratedPayouts = transfers.stream().map(t -> {
                String rowStatus = reversed.contains(t.movementId()) ? "VOIDED" : t.status();
                boolean awaitingCash = workflowVersion == 2 && courierTransfers.stream()
                        .anyMatch(f -> "PENDING".equals(f.status()) && f.toCourierAccountId().equals(t.courierAccountId()));
                String blocked = !"PENDING".equals(rowStatus) ? null
                        : !"ACTIVE".equals(batchStatus) ? "Revalidez la préparation avant de confirmer."
                        : awaitingCash ? "Confirmez d'abord les transferts entrants de ce livreur."
                        : capacities.getOrDefault(t.courierAccountId(), 0L) < t.amountAriary() ? "La caisse réelle du livreur ne couvre pas ce versement." : null;
                return new Transfer(t.id(), t.sellerAccountId(), t.sellerName(), t.sellerPayoutNumber(), t.courierAccountId(),
                        t.courierName(), t.amountAriary(), rowStatus, t.movementId(), t.sellerPayoutNumberOverride(),
                        t.sellerPayoutDestinationId(), t.sellerPayoutAccountHolder(), t.sellerPayoutOperator(),
                        "PENDING".equals(rowStatus) && blocked == null, blocked);
            }).toList();
            List<CourierTransfer> decoratedFunding = courierTransfers.stream().map(t -> {
                String rowStatus = reversed.contains(t.movementId()) ? "VOIDED" : t.status();
                String blocked = !"PENDING".equals(rowStatus) ? null
                        : !"ACTIVE".equals(batchStatus) ? "Revalidez la préparation avant de confirmer."
                        : capacities.getOrDefault(t.fromCourierAccountId(), 0L) < t.amountAriary()
                            ? "Confirmez d'abord les apports nécessaires : la caisse réelle du donneur est insuffisante." : null;
                return new CourierTransfer(t.id(), t.fromCourierAccountId(), t.fromCourierName(), t.toCourierAccountId(),
                        t.toCourierName(), t.amountAriary(), rowStatus, t.movementId(), "PENDING".equals(rowStatus) && blocked == null, blocked);
            }).toList();
            return new Batch(id, rs.getObject("plan_id", UUID.class), rs.getObject("service_date", LocalDate.class), status,
                    rs.getLong("ledger_revision"), rs.getTimestamp("expires_at").toInstant(), CashPayoutOptimizer.ALGORITHM,
                    decoratedPayouts, balances, message, mode, decoratedFunding, workflowVersion);
        }, id).stream().findFirst().orElseThrow(() -> new FinanceConflictException("Cette proposition n'existe plus."));
    }

    private Transfer transfer(ResultSet rs) throws SQLException {
        return new Transfer(rs.getObject("id", UUID.class), rs.getObject("seller_account_id", UUID.class), rs.getString("seller_name"),
                rs.getString("seller_payout_number"), rs.getObject("courier_account_id", UUID.class), rs.getString("courier_name"),
                rs.getLong("amount_ariary"), rs.getString("status"), rs.getObject("movement_id", UUID.class),
                rs.getBoolean("seller_payout_number_override"), rs.getObject("seller_payout_destination_id", UUID.class),
                rs.getString("seller_payout_account_holder"), rs.getString("seller_payout_operator"));
    }

    private boolean payoutChanged(Transfer transfer, Map<UUID, List<ClientPayoutDestination>> destinations) {
        if (!"PENDING".equals(transfer.status())) return false;
        if (!validPayoutNumber(transfer.sellerPayoutNumber()) || !validAccountHolder(transfer.sellerPayoutAccountHolder(), 200)) return true;
        if (transfer.sellerPayoutNumberOverride()) return false;
        ClientPayoutDestination destination = linkedDestination(transfer, destinations);
        return !validDestination(destination) || !Objects.equals(transfer.sellerPayoutNumber(), destination.number())
                || !Objects.equals(transfer.sellerPayoutAccountHolder(), destination.accountHolder())
                || !Objects.equals(transfer.sellerPayoutOperator(), snapshotOperator(destination));
    }

    private String transferMessage(String batchStatus) {
        return "STALE".equals(batchStatus)
                ? "Les caisses ou les coordonnées de versement ont changé : revalidez avant de transmettre le briefing."
                : "Proposition uniquement. Confirmez chaque versement réel dans Finance.";
    }

    private static void conflict(String message) { throw new FinanceConflictException(message); }
}
