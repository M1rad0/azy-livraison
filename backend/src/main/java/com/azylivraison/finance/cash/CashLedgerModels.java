package com.azylivraison.finance.cash;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.finance.domain.FinanceModels.ProofMetadata;

/** Cash ownership is independent of courier compensation and of seller debt. */
public final class CashLedgerModels {
    private CashLedgerModels() { }

    public record Account(String key, String type, UUID entityId, String name,
            long balanceAriary, long reservedAriary, long availableAriary, String payoutNumber) { }

    public record Movement(UUID id, String kind, String fromKey, String toKey, long amountAriary,
            Long deltaAriary, String paymentMethod, String reference, String note, String status,
            Instant createdAt, UUID actorAccountId, boolean proofAvailable, UUID reversalOf,
            String voidReason, Instant voidedAt) { }

    public record Obligation(UUID id, UUID reportId, UUID sellerAccountId, LocalDate serviceDate,
            long amountDueAriary, long paidAriary, long remainingAriary, boolean active) { }

    public record Snapshot(Instant activatedAt, long revision, List<Account> accounts,
            List<Movement> movements, List<Obligation> obligations) { }

    public record CashPage<T>(List<T> items, long totalElements, int page, int size, long totalPages) {
        public CashPage(List<T> items, long totalElements, CashListQuery query) {
            this(List.copyOf(items), totalElements, query.page(), query.size(),
                    totalElements / query.size() + (totalElements % query.size() == 0 ? 0 : 1));
        }
    }

    public record TransferRequest(String idempotencyKey, String fromKey, String toKey,
            long amountAriary, String paymentMethod, String reference, String note, Long expectedRevision) { }

    public record AdjustmentRequest(String idempotencyKey, String accountKey, long deltaAriary,
            String reason, Long expectedRevision) { }

    public record VoidRequest(String reason, Long expectedRevision) { }

    record MovementEntry(UUID id, String kind, String idempotencyKey, String fingerprint,
            String fromKey, String toKey, long amountAriary, Long deltaAriary, String paymentMethod,
            String reference, String note, UUID sourceReference, UUID reversalOf, UUID actorAccountId,
            ProofMetadata proof) { }

    record Leg(String accountKey, long deltaAriary) { }

    record SellerPayment(UUID movementId, long unallocatedSignedAriary) { }

    record CollectionState(UUID packageId, UUID courierId, long observedAmountAriary,
            UUID baselineCourierId, long baselineAmountAriary, long version) { }

    record ObligationState(UUID id, UUID reportId, UUID sellerAccountId, LocalDate serviceDate,
            long baselineAmountAriary, long observedAmountAriary, long recognizedAmountAriary,
            boolean active, long version) { }
}
