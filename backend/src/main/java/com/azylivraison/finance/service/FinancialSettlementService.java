package com.azylivraison.finance.service;

import static com.azylivraison.finance.domain.FinanceModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.account.domain.Account;
import com.azylivraison.account.repository.AccountRepository;
import com.azylivraison.dailyworkflow.service.DailyWorkflowConflictException;
import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.finance.render.CourierStatementPdfRenderer;
import com.azylivraison.finance.render.CourierStatementXlsxRenderer;
import com.azylivraison.finance.repository.FinanceRepository;
import com.azylivraison.reporting.domain.DailyReport;
import com.azylivraison.reporting.service.DailyReportService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FinancialSettlementService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Indian/Antananarivo");

    private final FinanceRepository repository;
    private final DailyReportService dailyReportService;
    private final AccountRepository accountRepository;
    private final PaymentProofStorage proofStorage;
    private final CourierStatementPdfRenderer courierStatementPdfRenderer;
    private final CourierStatementXlsxRenderer courierStatementXlsxRenderer;

    public FinancialSettlementService(
            FinanceRepository repository,
            DailyReportService dailyReportService,
            AccountRepository accountRepository,
            PaymentProofStorage proofStorage,
            CourierStatementPdfRenderer courierStatementPdfRenderer,
            CourierStatementXlsxRenderer courierStatementXlsxRenderer) {
        this.repository = repository;
        this.dailyReportService = dailyReportService;
        this.accountRepository = accountRepository;
        this.proofStorage = proofStorage;
        this.courierStatementPdfRenderer = courierStatementPdfRenderer;
        this.courierStatementXlsxRenderer = courierStatementXlsxRenderer;
    }

    /** Idempotent hook intended to be called immediately after a day is closed. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public List<ClientDailySettlement> materializeClientSettlements(LocalDate serviceDate) {
        DailyReport report = dailyReportService.getGeneralReport(serviceDate);
        List<ClientDailySettlement> settlements = new ArrayList<>();
        for (DailyReport.ClientSummary client : report.clients()) {
            settlements.add(ensureSettlement(
                    report.reportId(), report.serviceDate(), report.closure().version(),
                    client.accountId(), client.displayName(), client.totals().amountToRemitAriary(),
                    report.generatedAt()));
        }
        return List.copyOf(settlements);
    }

    /**
     * Guard intended for the operational workflow before reopening a closed day.
     * Voided payments do not block; finalized courier earnings are immutable.
     */
    @Transactional(readOnly = true)
    public void assertDayCanReopen(LocalDate serviceDate) {
        if (repository.clientHasConfirmedPaymentsOnDate(serviceDate)) {
            throw new DailyWorkflowConflictException(
                    "Annulez d'abord tous les versements clients confirmés de cette journée.");
        }
        if (repository.hasCompensationBatchOnDate(serviceDate)) {
            throw new DailyWorkflowConflictException(
                    "Les rémunérations des livreurs ont déjà été validées pour cette journée, qui ne peut plus être rouverte.");
        }
    }

    @Transactional
    public ClientFinance getClientFinance(UUID clientAccountId) {
        requireAccount(clientAccountId);
        materializeKnownClientReports(clientAccountId);
        List<ClientDailySettlement> settlements = repository.listActiveSettlements(clientAccountId);
        List<ClientPayment> payments = repository.listClientPayments(clientAccountId);
        long totalDue = 0;
        long totalPaid = 0;
        for (ClientDailySettlement settlement : settlements) {
            totalDue = Math.addExact(totalDue, settlement.amountDueAriary());
            totalPaid = Math.addExact(totalPaid, settlement.confirmedSignedPaymentsAriary());
        }
        long balance = Math.subtractExact(totalDue, totalPaid);
        return new ClientFinance(
                clientAccountId, repository.findPreferredClientPayoutDestination(clientAccountId).orElse(null),
                totalDue, totalPaid, balance,
                Math.max(balance, 0), balance == Long.MIN_VALUE ? Long.MAX_VALUE : Math.max(-balance, 0),
                settlements, payments);
    }

    @Transactional
    public ClientPayment createClientPayment(
            UUID settlementId,
            ClientPaymentDirection direction,
            long amountAriary,
            String paymentMethod,
            PaymentDestination destination,
            String transactionReference,
            String note,
            Instant confirmedAt,
            UUID actorAccountId,
            MultipartFile proofFile) {
        if (direction == null) throw new FinanceInputException("Indiquez le sens du versement.");
        requirePositiveAmount(amountAriary);
        String normalizedMethod = required(paymentMethod, 80, "Indiquez la méthode de versement.");
        PaymentDestination normalizedDestination = normalizeDestination(destination);
        ClientDailySettlement settlement = repository.lockSettlement(settlementId)
                .orElseThrow(() -> new FinanceNotFoundException("Ce rapport client est introuvable."));
        ClosedDay day = repository.lockDay(settlement.serviceDate())
                .orElseThrow(() -> new FinanceConflictException(
                        "Le versement ne peut être lié qu'au rapport actif d'une journée clôturée."));
        if (!"CLOSED".equals(day.status())) {
            throw new FinanceConflictException(
                    "Le versement ne peut être lié qu'au rapport actif d'une journée clôturée.");
        }
        if (day.closureRevision() != settlement.closureRevision()) {
            throw new FinanceConflictException("Ce rapport a été remplacé par une clôture plus récente.");
        }
        ProofMetadata proof = storeProofWithRollbackCleanup(proofFile);
        ClientPayment payment = new ClientPayment(
                UUID.randomUUID(), settlement.id(), settlement.clientAccountId(), settlement.serviceDate(),
                direction, amountAriary, normalizedMethod, normalizedDestination,
                optional(transactionReference, 200), optional(note, 1000), proof,
                PaymentStatus.CONFIRMED, confirmedAt == null ? Instant.now() : confirmedAt,
                actorAccountId, null, null, null, 0);
        repository.insertClientPayment(payment);
        return payment;
    }

    @Transactional
    public ClientPayment voidClientPayment(
            UUID paymentId,
            long expectedVersion,
            String reason,
            UUID actorAccountId) {
        ClientPayment payment = repository.lockClientPayment(paymentId)
                .orElseThrow(() -> new FinanceNotFoundException("Ce versement client est introuvable."));
        if (payment.status() == PaymentStatus.VOIDED) {
            throw new FinanceConflictException("Ce versement client est déjà annulé.");
        }
        String normalizedReason = required(reason, 1000, "Indiquez la raison de l'annulation.");
        if (!repository.voidClientPayment(paymentId, expectedVersion, actorAccountId, normalizedReason, Instant.now())) {
            throw new FinanceConflictException(
                    "Ce versement a été modifié entre-temps. Rechargez la page avant de réessayer.");
        }
        return repository.findClientPayment(paymentId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public ProofDownload getClientPaymentProofForAdmin(UUID paymentId) {
        ClientPayment payment = repository.findClientPayment(paymentId)
                .orElseThrow(() -> new FinanceNotFoundException("Ce versement client est introuvable."));
        return proofStorage.load(payment.proof());
    }

    @Transactional(readOnly = true)
    public ProofDownload getClientPaymentProof(UUID paymentId, UUID clientAccountId) {
        ClientPayment payment = repository.findClientPayment(paymentId)
                .filter(item -> item.clientAccountId().equals(clientAccountId))
                .orElseThrow(() -> new FinanceNotFoundException("Ce justificatif est introuvable."));
        return proofStorage.load(payment.proof());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CourierCompensationPreview previewCompensations(LocalDate serviceDate) {
        ClosedDay day = requireClosedDay(serviceDate);
        return repository.findCompensationBatch(serviceDate, day.closureRevision())
                .map(batch -> finalizedPreview(batch, repository.compensationSourceLines(serviceDate)))
                .orElseGet(() -> livePreview(day));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CourierCompensationPreview finalizeCompensations(
            LocalDate serviceDate,
            int expectedClosureRevision,
            List<CompensationOverride> requestedOverrides,
            UUID actorAccountId) {
        ClosedDay day = repository.lockDay(serviceDate)
                .orElseThrow(() -> new FinanceNotFoundException("Cette journée est introuvable."));
        if (!"CLOSED".equals(day.status())) {
            throw new FinanceConflictException("Clôturez la journée avant de valider les rémunérations.");
        }
        if (day.closureRevision() != expectedClosureRevision) {
            throw new FinanceConflictException(
                    "La clôture a été modifiée entre-temps. Rechargez la page avant de continuer.");
        }
        if (repository.findCompensationBatch(serviceDate, day.closureRevision()).isPresent()) {
            throw new FinanceConflictException("Les rémunérations de cette journée sont déjà validées.");
        }
        List<CompensationSourceLine> sources = repository.compensationSourceLines(serviceDate);
        long pendingReturns = sources.stream().filter(source -> !source.eligible()).count();
        if (pendingReturns > 0) {
            throw new FinanceConflictException(
                    "Confirmez d'abord le retour de " + pendingReturns
                            + " colis avant de valider les rémunérations.");
        }
        Map<UUID, CompensationOverride> overrides = normalizeOverrides(requestedOverrides, sources);
        Instant now = Instant.now();
        List<CourierEarning> earnings = new ArrayList<>();
        long totalSuggested = 0;
        long totalFinal = 0;
        for (CompensationSourceLine source : sources) {
            long suggested = suggestedAmount(source.deliveryFeeAriary(),
                    source.commissionNumerator(), source.commissionDenominator());
            CompensationOverride override = overrides.get(source.packageId());
            long finalAmount = override == null ? suggested : override.finalAmountAriary();
            String overrideReason = finalAmount == suggested ? null : required(
                    override.reason(), 1000,
                    "Expliquez toute rémunération différente du montant suggéré.");
            totalSuggested = Math.addExact(totalSuggested, suggested);
            totalFinal = Math.addExact(totalFinal, finalAmount);
            earnings.add(new CourierEarning(
                    UUID.randomUUID(), serviceDate, day.closureRevision(), source.packageId(),
                    source.courierId(), source.courierName(), source.courierContact(), source.outcome(),
                    source.deliveryFeeAriary(), source.commissionNumerator(), source.commissionDenominator(),
                    suggested, finalAmount, overrideReason, now));
        }
        CompensationBatch batch = new CompensationBatch(
                serviceDate, day.closureRevision(), totalSuggested, totalFinal,
                now, actorAccountId, 0);
        repository.insertCompensationBatch(batch);
        earnings.forEach(repository::insertEarning);
        return finalizedPreview(batch, sources);
    }

    @Transactional(readOnly = true)
    public CourierFinance getCourierFinance(UUID courierId, LocalDate from, LocalDate to) {
        validateDateRange(from, to);
        String courierName = repository.findCourierName(courierId)
                .orElseThrow(() -> new FinanceNotFoundException("Ce livreur est introuvable."));
        long totalEarned = repository.totalCourierEarnings(courierId);
        long totalPaid = repository.totalConfirmedCourierPayments(courierId);
        long balance = Math.subtractExact(totalEarned, totalPaid);
        Instant fromInstant = from == null ? null : from.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        return new CourierFinance(
                courierId, courierName, totalEarned, totalPaid,
                Math.max(balance, 0), balance == Long.MIN_VALUE ? Long.MAX_VALUE : Math.max(-balance, 0),
                repository.listCourierDeliveries(courierId, from, to),
                repository.listCourierEarnings(courierId, from, to),
                repository.listCourierPayments(courierId, fromInstant, toExclusive));
    }

    @Transactional(readOnly = true)
    public byte[] renderCourierStatementPdf(UUID courierId, LocalDate from, LocalDate to) {
        return courierStatementPdfRenderer.render(
                getCourierFinance(courierId, from, to), from, to, Instant.now());
    }

    @Transactional(readOnly = true)
    public byte[] renderCourierStatementXlsx(UUID courierId, LocalDate from, LocalDate to) {
        return courierStatementXlsxRenderer.render(
                getCourierFinance(courierId, from, to), from, to, Instant.now());
    }

    @Transactional
    public CourierPayment createCourierPayment(
            UUID courierId,
            long amountAriary,
            String paymentMethod,
            PaymentDestination destination,
            String transactionReference,
            String note,
            Instant confirmedAt,
            UUID actorAccountId,
            MultipartFile proofFile) {
        repository.findCourierName(courierId)
                .orElseThrow(() -> new FinanceNotFoundException("Ce livreur est introuvable."));
        requirePositiveAmount(amountAriary);
        ProofMetadata proof = storeProofWithRollbackCleanup(proofFile);
        CourierPayment payment = new CourierPayment(
                UUID.randomUUID(), courierId, amountAriary,
                required(paymentMethod, 80, "Indiquez la méthode de versement."),
                normalizeDestination(destination), optional(transactionReference, 200),
                optional(note, 1000), proof, PaymentStatus.CONFIRMED,
                confirmedAt == null ? Instant.now() : confirmedAt,
                actorAccountId, null, null, null, 0);
        repository.insertCourierPayment(payment);
        return payment;
    }

    @Transactional
    public CourierPayment voidCourierPayment(
            UUID paymentId,
            long expectedVersion,
            String reason,
            UUID actorAccountId) {
        CourierPayment payment = repository.lockCourierPayment(paymentId)
                .orElseThrow(() -> new FinanceNotFoundException("Ce versement livreur est introuvable."));
        if (payment.status() == PaymentStatus.VOIDED) {
            throw new FinanceConflictException("Ce versement livreur est déjà annulé.");
        }
        String normalizedReason = required(reason, 1000, "Indiquez la raison de l'annulation.");
        if (!repository.voidCourierPayment(paymentId, expectedVersion, actorAccountId, normalizedReason, Instant.now())) {
            throw new FinanceConflictException(
                    "Ce versement a été modifié entre-temps. Rechargez la page avant de réessayer.");
        }
        return repository.findCourierPayment(paymentId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public ProofDownload getCourierPaymentProof(UUID paymentId) {
        CourierPayment payment = repository.findCourierPayment(paymentId)
                .orElseThrow(() -> new FinanceNotFoundException("Ce versement livreur est introuvable."));
        return proofStorage.load(payment.proof());
    }

    long suggestedAmount(long deliveryFeeAriary, int numerator, int denominator) {
        if (deliveryFeeAriary < 0 || numerator <= 0 || denominator <= 0 || numerator > denominator) {
            throw new FinanceInputException("La fraction de rémunération du livreur est invalide.");
        }
        return BigDecimal.valueOf(deliveryFeeAriary)
                .multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private void materializeKnownClientReports(UUID clientAccountId) {
        Account account = requireAccount(clientAccountId);
        for (DailyReportService.ClientReportSnapshot summary
                : dailyReportService.listClientReportSnapshots(clientAccountId)) {
            ensureSettlement(
                    summary.reportId(), summary.serviceDate(), summary.revision(), clientAccountId,
                    account.displayName(), summary.totals().amountToRemitAriary(), summary.closedAt());
        }
    }

    private ClientDailySettlement ensureSettlement(
            UUID generalReportId,
            LocalDate serviceDate,
            int closureRevision,
            UUID clientAccountId,
            String clientDisplayName,
            long amountDueAriary,
            Instant createdAt) {
        Optional<ClientDailySettlement> existing = repository.findSettlement(
                serviceDate, closureRevision, clientAccountId);
        if (existing.isPresent()) return existing.get();
        UUID clientReportId = DailyReportService.deriveClientReportId(generalReportId, clientAccountId);
        ClientDailySettlement settlement = new ClientDailySettlement(
                UUID.randomUUID(), clientReportId, serviceDate, closureRevision, clientAccountId,
                clientDisplayName == null || clientDisplayName.isBlank()
                        ? clientAccountId.toString() : clientDisplayName,
                amountDueAriary, 0, createdAt == null ? Instant.now() : createdAt);
        if (repository.insertSettlement(settlement)) return settlement;
        return repository.findSettlement(serviceDate, closureRevision, clientAccountId)
                .orElseThrow(() -> new FinanceConflictException(
                        "Le relevé client n'a pas pu être matérialisé de manière cohérente."));
    }

    private CourierCompensationPreview livePreview(ClosedDay day) {
        List<CompensationLine> lines = new ArrayList<>();
        long totalSuggested = 0;
        int pendingReturns = 0;
        for (CompensationSourceLine source : repository.compensationSourceLines(day.serviceDate())) {
            long suggested = suggestedAmount(
                    source.deliveryFeeAriary(), source.commissionNumerator(), source.commissionDenominator());
            if (source.eligible()) {
                totalSuggested = Math.addExact(totalSuggested, suggested);
            } else {
                pendingReturns++;
            }
            lines.add(new CompensationLine(
                    null, source.packageId(), source.courierId(), source.courierName(), source.courierContact(),
                    source.outcome(), source.eligible(), source.eligible() ? null : "Retour à l'envoyeur non confirmé.",
                    source.deliveryFeeAriary(), source.commissionNumerator(), source.commissionDenominator(),
                    suggested, source.eligible() ? suggested : null, null,
                    source.recipientName(), source.destinationLabel()));
        }
        return new CourierCompensationPreview(
                day.serviceDate(), day.closureRevision(), false, null, null,
                totalSuggested, totalSuggested, pendingReturns, lines);
    }

    private CourierCompensationPreview finalizedPreview(
            CompensationBatch batch,
            List<CompensationSourceLine> sourceLines) {
        Map<UUID, CompensationSourceLine> sources = new HashMap<>();
        sourceLines.forEach(source -> sources.put(source.packageId(), source));
        List<CompensationLine> lines = repository.listBatchEarnings(
                        batch.serviceDate(), batch.closureRevision()).stream()
                .map(earning -> {
                    CompensationSourceLine source = sources.get(earning.packageId());
                    return new CompensationLine(
                            earning.id(), earning.packageId(), earning.courierId(), earning.courierName(),
                            earning.courierContact(), earning.outcome(), true, null,
                            earning.deliveryFeeAriary(), earning.commissionNumerator(),
                            earning.commissionDenominator(), earning.suggestedAmountAriary(),
                            earning.finalAmountAriary(), earning.overrideReason(),
                            source == null ? null : source.recipientName(),
                            source == null ? null : source.destinationLabel());
                }).toList();
        return new CourierCompensationPreview(
                batch.serviceDate(), batch.closureRevision(), true,
                batch.finalizedAt(), batch.finalizedByAccountId(),
                batch.totalSuggestedAriary(), batch.totalFinalAriary(), 0, lines);
    }

    private Map<UUID, CompensationOverride> normalizeOverrides(
            List<CompensationOverride> requested,
            List<CompensationSourceLine> sources) {
        Map<UUID, CompensationSourceLine> available = new HashMap<>();
        sources.forEach(source -> available.put(source.packageId(), source));
        Map<UUID, CompensationOverride> overrides = new LinkedHashMap<>();
        for (CompensationOverride override : requested == null ? List.<CompensationOverride>of() : requested) {
            if (override == null || override.packageId() == null || override.finalAmountAriary() < 0) {
                throw new FinanceInputException("Une rémunération finale est invalide.");
            }
            if (!available.containsKey(override.packageId())) {
                throw new FinanceInputException("Un colis modifié ne fait pas partie de cette clôture.");
            }
            if (overrides.putIfAbsent(override.packageId(), override) != null) {
                throw new FinanceInputException("Un colis est présent plusieurs fois dans les ajustements.");
            }
        }
        return Map.copyOf(overrides);
    }

    private ClosedDay requireClosedDay(LocalDate serviceDate) {
        if (serviceDate == null) throw new FinanceInputException("Indiquez la date d'activité.");
        return repository.findClosedDay(serviceDate)
                .orElseThrow(() -> new FinanceConflictException(
                        "Clôturez la journée avant d'accéder à ses informations financières."));
    }

    private Account requireAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new FinanceNotFoundException("Ce compte client est introuvable."));
    }

    private ProofMetadata storeProofWithRollbackCleanup(MultipartFile proofFile) {
        ProofMetadata proof = proofStorage.store(proofFile);
        if (proof != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        proofStorage.deleteQuietly(proof.storageKey());
                    }
                }
            });
        }
        return proof;
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new FinanceInputException("La date de début doit précéder la date de fin.");
        }
    }

    private void requirePositiveAmount(long amountAriary) {
        if (amountAriary <= 0) throw new FinanceInputException("Le montant doit être positif.");
    }

    private PaymentDestination normalizeDestination(PaymentDestination destination) {
        if (destination == null) return null;
        return new PaymentDestination(
                required(destination.operator(), 80, "Indiquez l'opérateur du versement."),
                required(destination.accountHolder(), 150, "Indiquez le titulaire du compte."),
                required(destination.number(), 100, "Indiquez le numéro de versement."));
    }

    private String required(String value, int maxLength, String message) {
        String normalized = optional(value, maxLength);
        if (normalized == null) throw new FinanceInputException(message);
        return normalized;
    }

    private String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new FinanceInputException("Une information dépasse la longueur autorisée.");
        }
        return normalized;
    }
}
