package com.azylivraison.finance.cash;

import static com.azylivraison.finance.cash.CashLedgerModels.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.azylivraison.finance.domain.FinanceModels.ProofDownload;
import com.azylivraison.finance.domain.FinanceModels.ProofMetadata;
import com.azylivraison.finance.service.FinanceConflictException;
import com.azylivraison.finance.service.FinanceInputException;
import com.azylivraison.finance.service.FinanceNotFoundException;
import com.azylivraison.finance.service.PaymentProofStorage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/** Cash custody is separate from seller debt and from wages. Every writer holds one ledger lock. */
@Service
public class CashLedgerService {
    public static final long MAX_SAFE_ARIARY = 9_007_199_254_740_991L;
    private final CashLedgerRepository repository;
    private final PaymentProofStorage proofStorage;

    public CashLedgerService(CashLedgerRepository repository, PaymentProofStorage proofStorage) {
        this.repository = repository;
        this.proofStorage = proofStorage;
    }

    /** Transactional briefing orchestration keeps this lock until commit. */
    public void lock() { repository.lock(); repository.ensureAccounts(); }
    public long revision() { return repository.revision(); }
    @Transactional public Snapshot getSnapshot() {
        return getSnapshot(true);
    }
    @Transactional public Snapshot getSnapshot(boolean includeHistory) {
        lock(); repository.cleanupReservations(); return repository.snapshot(includeHistory);
    }
    @Transactional public Snapshot snapshot() { return getSnapshot(); }

    @Transactional(readOnly = true)
    public CashPage<Movement> movements(CashListQuery query) { return repository.movements(query); }

    @Transactional(readOnly = true)
    public CashPage<Obligation> obligations(CashListQuery query) { return repository.obligations(query); }

    @Transactional
    public Movement confirmTransfer(TransferRequest request, UUID actor, MultipartFile proof) {
        lock();
        return confirmTransferInternal(request, actor, proof, false);
    }

    /** Caller holds the lock. Briefing confirmations preserve the other reservations. */
    public Movement confirmTransferInternal(TransferRequest request, UUID actor, MultipartFile proof) {
        return confirmTransferInternal(request, actor, proof, true);
    }

    public Movement confirmTransferInternal(TransferRequest request, UUID actor, MultipartFile proof,
            boolean preserveReservations) {
        if (request == null) throw invalid("Renseignez le versement.");
        String key = manualKey(request.idempotencyKey());
        String from = accountKey(request.fromKey());
        String to = accountKey(request.toKey());
        if (from.equals(to)) throw invalid("Choisissez deux caisses différentes.");
        positive(request.amountAriary());
        requireActor(actor);
        String method = required(request.paymentMethod(), 80, "Indiquez le moyen de versement.");
        String reference = optional(request.reference(), 200);
        String note = optional(request.note(), 1000);
        String hash = fingerprint("TRANSFER", from, to, request.amountAriary(), method,
                reference, note, actor, proofFingerprint(proof));
        Movement retry = retry(key, hash);
        if (retry != null) return retry;
        expectedRevision(request.expectedRevision());
        Account source = requireAccount(from);
        Account destination = requireAccount(to);
        List<Leg> legs = List.of(new Leg(from, source.type().equals("SELLER")
                ? request.amountAriary() : -request.amountAriary()), new Leg(to,
                destination.type().equals("SELLER") ? -request.amountAriary() : request.amountAriary()));
        validateBalances(legs);
        ProofMetadata storedProof = storeProof(proof);
        UUID id = UUID.randomUUID();
        try {
            repository.insert(new MovementEntry(id, "TRANSFER", key, hash, from, to,
                    request.amountAriary(), null, method, reference, note, null, null, actor, storedProof), legs);
            if (source.type().equals("SELLER")) allocate(id, source.entityId(), -request.amountAriary());
            if (destination.type().equals("SELLER")) allocate(id, destination.entityId(), request.amountAriary());
            changed(preserveReservations);
            return requireMovement(id);
        } catch (RuntimeException exception) {
            if (storedProof != null && !TransactionSynchronizationManager.isSynchronizationActive()) {
                proofStorage.deleteQuietly(storedProof.storageKey());
            }
            throw exception;
        }
    }

    /** Explicit reconciliation, including zeroing an account, always carries a reason. */
    @Transactional
    public Movement adjust(AdjustmentRequest request, UUID actor) {
        lock();
        if (request == null) throw invalid("Renseignez la régularisation.");
        String key = manualKey(request.idempotencyKey());
        String account = accountKey(request.accountKey());
        safe(request.deltaAriary());
        if (request.deltaAriary() == 0) throw invalid("La régularisation doit modifier le solde.");
        String reason = required(request.reason(), 1000, "Indiquez le motif de la régularisation.");
        requireActor(actor);
        String hash = fingerprint("ADJUSTMENT", account, request.deltaAriary(), reason, actor);
        Movement retry = retry(key, hash);
        if (retry != null) return retry;
        expectedRevision(request.expectedRevision());
        Account target = requireAccount(account);
        List<Leg> legs = List.of(new Leg(account, request.deltaAriary()));
        validateBalances(legs);
        UUID id = UUID.randomUUID();
        repository.insert(new MovementEntry(id, "ADJUSTMENT", key, hash, null, account,
                Math.abs(request.deltaAriary()), request.deltaAriary(), null, null, reason,
                null, null, actor, null), legs);
        if (target.type().equals("SELLER")) allocate(id, target.entityId(), -request.deltaAriary());
        changed(false);
        return requireMovement(id);
    }

    @Transactional
    public Movement voidMovement(UUID id, VoidRequest request, UUID actor) {
        lock();
        if (request == null) throw invalid("Indiquez le motif de l'annulation.");
        String reason = required(request.reason(), 1000, "Indiquez le motif de l'annulation.");
        requireActor(actor);
        MovementEntry original = repository.entry(id)
                .orElseThrow(() -> new FinanceNotFoundException("Ce mouvement est introuvable."));
        if (!List.of("TRANSFER", "ADJUSTMENT").contains(original.kind())) {
            throw invalid("Corrigez les encaissements et rapports depuis leur compte rendu d'origine.");
        }
        String key = "void:" + id;
        String hash = fingerprint("VOID", id, reason);
        Movement retry = retry(key, hash);
        if (retry != null) return requireMovement(id);
        expectedRevision(request.expectedRevision());
        List<Leg> opposite = repository.legs(id).stream()
                .map(leg -> new Leg(leg.accountKey(), -leg.deltaAriary())).toList();
        validateBalances(opposite);
        UUID reversal = UUID.randomUUID();
        repository.insert(new MovementEntry(reversal, "REVERSAL", key, hash,
                original.toKey(), original.fromKey(), original.amountAriary(),
                original.deltaAriary() == null ? null : -original.deltaAriary(),
                original.paymentMethod(), original.reference(), reason, original.sourceReference(),
                id, actor, null), opposite);
        repository.reverseAllocations(id, reversal);
        changed(false);
        return requireMovement(id);
    }

    @Transactional(readOnly = true)
    public ProofDownload getProof(UUID id) {
        MovementEntry entry = repository.entry(id)
                .orElseThrow(() -> new FinanceNotFoundException("Ce mouvement est introuvable."));
        if (entry.proof() == null) throw new FinanceNotFoundException("Aucun justificatif pour ce versement.");
        return proofStorage.load(entry.proof());
    }
    /** Migration baselines are observations only; repeating an observation posts nothing. */
    @Transactional
    public void syncCollection(UUID packageId, UUID courierId, long amount, UUID actor) {
        lock();
        if (packageId == null) throw invalid("Indiquez le colis encaissé.");
        safe(amount);
        if (amount < 0) throw invalid("L'encaissement ne peut pas être négatif.");
        if (amount > 0 && courierId == null) throw invalid("Indiquez le livreur ayant encaissé l'argent.");
        requireActor(actor);
        CollectionState previous = repository.collection(packageId).orElse(
                new CollectionState(packageId, null, 0, null, 0, 0));
        if (previous.observedAmountAriary() == amount && Objects.equals(previous.courierId(), courierId)) return;
        List<Leg> legs = collectionDelta(previous, courierId, amount);
        if (!legs.isEmpty()) {
            validateBalances(legs);
            long version = Math.addExact(previous.version(), 1);
            String from = legs.stream().filter(leg -> leg.deltaAriary() < 0)
                    .map(Leg::accountKey).findFirst().orElse(null);
            String to = legs.stream().filter(leg -> leg.deltaAriary() > 0)
                    .map(Leg::accountKey).findFirst().orElse(null);
            long magnitude = legs.stream().mapToLong(leg -> Math.abs(leg.deltaAriary())).max().orElseThrow();
            repository.insert(new MovementEntry(UUID.randomUUID(), "COLLECTION", "collection:" + packageId + ":" + version,
                    fingerprint(packageId, courierId, amount, version), from, to, magnitude,
                    safeSubtract(amount, previous.observedAmountAriary()), null, null,
                    "Encaissement réel du compte rendu", packageId, null, actor, null), legs);
            changed(false);
        }
        repository.saveCollection(new CollectionState(packageId, courierId, amount,
                previous.baselineCourierId(), previous.baselineAmountAriary(), Math.addExact(previous.version(), 1)));
    }

    /** Recognize a newly closed seller report, not historic balances. */
    @Transactional
    public void syncSellerReport(UUID reportId, LocalDate date, UUID seller, long netDue, UUID actor) {
        lock();
        if (reportId == null || seller == null || date == null) throw invalid("Le rapport client est incomplet.");
        safe(netDue);
        requireActor(actor);
        String key = sellerKey(seller);
        requireAccount(key);
        ObligationState previous = repository.obligation(reportId, seller).orElseGet(() ->
                new ObligationState(UUID.randomUUID(), reportId, seller, date,
                        repository.openingSellerBaseline(date, seller), 0, 0, false, 0));
        long recognized = safeSubtract(netDue, previous.baselineAmountAriary());
        if (previous.active() && previous.observedAmountAriary() == netDue
                && previous.recognizedAmountAriary() == recognized) return;
        if (repository.hasPayments(reportId)) {
            throw new FinanceConflictException("Annulez les versements liés au rapport avant de le modifier.");
        }
        long oldRecognized = previous.active() ? previous.recognizedAmountAriary() : 0;
        long delta = safeSubtract(recognized, oldRecognized);
        long version = Math.addExact(previous.version(), 1);
        if (delta != 0) {
            postObligation(reportId, seller, delta, actor, "seller-report:" + previous.id() + ":" + version,
                    "Solde du rapport client clôturé");
        }
        repository.saveObligation(new ObligationState(previous.id(), reportId, seller, date,
                previous.baselineAmountAriary(), netDue, recognized, true, version));
        // An earlier confirmed advance is cash already sent, not a new collection.
        for (SellerPayment payment : repository.unallocatedSellerPayments(seller)) {
            allocate(payment.movementId(), seller, payment.unallocatedSignedAriary());
        }
        changed(false);
    }

    @Transactional
    public void reopenSellerReport(UUID reportId, UUID actor) {
        lock();
        requireActor(actor);
        if (repository.hasPayments(reportId)) {
            throw new FinanceConflictException("Annulez les versements liés au rapport avant de le rouvrir.");
        }
        boolean updated = false;
        for (ObligationState state : repository.reportObligations(reportId)) {
            if (!state.active()) continue;
            long version = Math.addExact(state.version(), 1);
            if (state.recognizedAmountAriary() != 0) {
                postObligation(reportId, state.sellerAccountId(), -state.recognizedAmountAriary(), actor,
                        "reopen-report:" + state.id() + ":" + version, "Réouverture du rapport client");
            }
            repository.saveObligation(new ObligationState(state.id(), state.reportId(), state.sellerAccountId(),
                    state.serviceDate(), state.baselineAmountAriary(), state.observedAmountAriary(),
                    state.recognizedAmountAriary(), false, version));
            updated = true;
        }
        if (updated) changed(false);
    }
    private void postObligation(UUID reportId, UUID seller, long delta, UUID actor, String idempotencyKey, String note) {
        String key = sellerKey(seller);
        List<Leg> legs = List.of(new Leg(key, delta));
        validateBalances(legs);
        repository.insert(new MovementEntry(UUID.randomUUID(), "SELLER_OBLIGATION", idempotencyKey,
                fingerprint(reportId, seller, delta, idempotencyKey), null, key, Math.abs(delta), delta,
                null, null, note, reportId, null, actor, null), legs);
    }

    private List<Leg> collectionDelta(CollectionState previous, UUID courier, long amount) {
        Map<String, Long> deltas = new LinkedHashMap<>();
        if (previous.courierId() != null && previous.observedAmountAriary() != 0) {
            deltas.put(courierKey(previous.courierId()), -previous.observedAmountAriary());
        }
        if (courier != null && amount != 0) deltas.merge(courierKey(courier), amount, CashLedgerService::safeAdd);
        return deltas.entrySet().stream().filter(entry -> entry.getValue() != 0)
                .map(entry -> new Leg(entry.getKey(), entry.getValue())).toList();
    }

    /** Allocate signed payments to oldest same-sign reports; never invent a report. */
    private void allocate(UUID movementId, UUID seller, long signedPayment) {
        long remaining = signedPayment;
        for (Obligation obligation : repository.obligations()) {
            if (!obligation.active() || !seller.equals(obligation.sellerAccountId())
                    || Long.signum(obligation.remainingAriary()) != Long.signum(remaining)) continue;
            long applied = Long.signum(remaining)
                    * Math.min(Math.abs(remaining), Math.abs(obligation.remainingAriary()));
            if (applied != 0) repository.allocate(movementId, obligation.id(), applied);
            remaining = safeSubtract(remaining, applied);
            if (remaining == 0) break;
        }
    }

    private Movement retry(String key, String hash) {
        return repository.findByIdempotencyKey(key).map(existing -> {
            if (!existing.fingerprint().equals(hash)) {
                throw new FinanceConflictException("Cette opération a déjà été envoyée avec des informations différentes.");
            }
            return requireMovement(existing.id());
        }).orElse(null);
    }

    private void expectedRevision(Long expected) {
        if (expected != null && expected != revision()) {
            throw new FinanceConflictException("Les caisses ont changé. Actualisez avant de confirmer.");
        }
    }

    private void changed(boolean preserveReservations) {
        if (!preserveReservations) repository.releaseReservations();
        repository.advanceRevision();
    }

    private void validateBalances(List<Leg> legs) {
        for (Leg leg : legs) safeAdd(requireAccount(leg.accountKey()).balanceAriary(), leg.deltaAriary());
    }

    private Account requireAccount(String key) {
        return repository.account(key).orElseThrow(() -> new FinanceNotFoundException("Cette caisse est introuvable."));
    }

    private Movement requireMovement(UUID id) {
        return repository.movement(id).orElseThrow(() -> new FinanceNotFoundException("Ce mouvement est introuvable."));
    }

    private ProofMetadata storeProof(MultipartFile file) {
        ProofMetadata proof = proofStorage.store(file);
        if (proof != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) proofStorage.deleteQuietly(proof.storageKey());
                }
            });
        }
        return proof;
    }

    private String proofFingerprint(MultipartFile file) {
        if (file == null || file.isEmpty()) return "";
        if (file.getSize() > 10L * 1024 * 1024) throw invalid("Le justificatif doit peser au maximum 10 Mo.");
        try {
            return fingerprint(file.getOriginalFilename(), HexFormat.of().formatHex(digest(file.getBytes())));
        } catch (IOException exception) { throw invalid("Le justificatif n'a pas pu être lu."); }
    }

    private static String fingerprint(Object... values) {
        StringBuilder text = new StringBuilder();
        for (Object value : values) {
            String part = value == null ? "" : value.toString();
            text.append(part.length()).append(':').append(part).append(';');
        }
        return HexFormat.of().formatHex(digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] digest(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static void requireActor(UUID actor) {
        if (actor == null) throw invalid("L'administrateur ayant confirmé l'opération est requis.");
    }

    private static String manualKey(String key) {
        return "manual:" + required(key, 180, "L'identifiant de l'opération est requis.");
    }

    private static String accountKey(String key) {
        return required(key, 120, "Choisissez une caisse.");
    }

    public static String courierKey(UUID id) { return "COURIER:" + id; }
    public static String sellerKey(UUID id) { return "SELLER:" + id; }

    private static String required(String value, int max, String message) {
        String result = optional(value, max);
        if (result == null) throw invalid(message);
        return result;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.strip();
        if (result.length() > max) throw invalid("Une information dépasse la longueur autorisée.");
        return result;
    }

    private static void positive(long value) {
        safe(value);
        if (value <= 0) throw invalid("Le montant doit être positif.");
    }

    private static long safe(long value) {
        if (value < -MAX_SAFE_ARIARY || value > MAX_SAFE_ARIARY) throw invalid("Le montant dépasse la limite autorisée.");
        return value;
    }

    private static long safeAdd(long left, long right) {
        try { return safe(Math.addExact(left, right)); }
        catch (ArithmeticException exception) { throw invalid("Le montant dépasse la limite autorisée."); }
    }

    private static long safeSubtract(long left, long right) {
        try { return safe(Math.subtractExact(left, right)); }
        catch (ArithmeticException exception) { throw invalid("Le montant dépasse la limite autorisée."); }
    }

    private static FinanceInputException invalid(String message) { return new FinanceInputException(message); }
}
