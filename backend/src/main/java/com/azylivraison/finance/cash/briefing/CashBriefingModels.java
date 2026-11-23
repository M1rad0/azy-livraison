package com.azylivraison.finance.cash.briefing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.azylivraison.account.domain.ClientPayoutDestination;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

public final class CashBriefingModels {
    private CashBriefingModels() {}

    public record PrepareRequest(@NotNull UUID planId, @NotBlank @Size(max = 100) String idempotencyKey,
            Long expectedLedgerRevision, UUID expectedBatchId, boolean replaceExisting) {
        public PrepareRequest(UUID planId, String idempotencyKey) { this(planId, idempotencyKey, null, null, false); }
    }
    public record ManualTransfer(@NotNull UUID sellerAccountId, @NotNull UUID courierAccountId,
            @Positive @JsonDeserialize(using = WholeAriaryDeserializer.class) long amountAriary,
            @Size(max = 100) String sellerPayoutNumber, UUID sellerPayoutDestinationId,
            @Size(max = 150) String sellerPayoutAccountHolder,
            @Size(max = 80) String sellerPayoutOperator) {
        public ManualTransfer(UUID sellerAccountId, UUID courierAccountId, long amountAriary) {
            this(sellerAccountId, courierAccountId, amountAriary, null, null, null, null);
        }
        public ManualTransfer(UUID sellerAccountId, UUID courierAccountId, long amountAriary, String sellerPayoutNumber) {
            this(sellerAccountId, courierAccountId, amountAriary, sellerPayoutNumber, null, null, null);
        }
        /** Compatibility constructor for requests persisted before custom operators were introduced. */
        public ManualTransfer(UUID sellerAccountId, UUID courierAccountId, long amountAriary,
                String sellerPayoutNumber, UUID sellerPayoutDestinationId, String sellerPayoutAccountHolder) {
            this(sellerAccountId, courierAccountId, amountAriary, sellerPayoutNumber,
                    sellerPayoutDestinationId, sellerPayoutAccountHolder, null);
        }
    }
    public record ManualRequest(@NotNull UUID planId, @NotBlank @Size(max = 100) String idempotencyKey,
            @NotNull Long expectedLedgerRevision, UUID expectedBatchId,
            @NotNull @Size(max = 1000) List<@Valid ManualTransfer> transfers,
            @Size(max = 1000) List<@Valid ManualCourierTransfer> courierTransfers) {
        public ManualRequest(UUID planId, String idempotencyKey, Long expectedLedgerRevision,
                UUID expectedBatchId, List<ManualTransfer> transfers) {
            this(planId, idempotencyKey, expectedLedgerRevision, expectedBatchId, transfers, null);
        }
    }
    public record ManualCourierTransfer(@NotNull UUID fromCourierAccountId, @NotNull UUID toCourierAccountId,
            @Positive @JsonDeserialize(using = WholeAriaryDeserializer.class) long amountAriary) {}
    public record ConfirmRequest(@NotBlank String paymentMethod, String reference, String note,
            @NotNull Long expectedLedgerRevision) {}
    public record Transfer(UUID id, UUID sellerAccountId, String sellerName, String sellerPayoutNumber,
            UUID courierAccountId, String courierName, long amountAriary, String status, UUID movementId,
            boolean sellerPayoutNumberOverride, UUID sellerPayoutDestinationId,
            String sellerPayoutAccountHolder, String sellerPayoutOperator,
            boolean readyToConfirm, String blockedReason) {
        public Transfer(UUID id, UUID sellerAccountId, String sellerName, String sellerPayoutNumber,
                UUID courierAccountId, String courierName, long amountAriary, String status, UUID movementId,
                boolean sellerPayoutNumberOverride, UUID sellerPayoutDestinationId,
                String sellerPayoutAccountHolder, String sellerPayoutOperator) {
            this(id, sellerAccountId, sellerName, sellerPayoutNumber, courierAccountId, courierName,
                    amountAriary, status, movementId, sellerPayoutNumberOverride, sellerPayoutDestinationId,
                    sellerPayoutAccountHolder, sellerPayoutOperator, false, null);
        }
        public Transfer(UUID id, UUID sellerAccountId, String sellerName, String sellerPayoutNumber,
                UUID courierAccountId, String courierName, long amountAriary, String status, UUID movementId) {
            this(id, sellerAccountId, sellerName, sellerPayoutNumber, courierAccountId, courierName,
                    amountAriary, status, movementId, false, null, null, null);
        }
        public Transfer(UUID id, UUID sellerAccountId, String sellerName, String sellerPayoutNumber,
                UUID courierAccountId, String courierName, long amountAriary, String status, UUID movementId,
                boolean sellerPayoutNumberOverride) {
            this(id, sellerAccountId, sellerName, sellerPayoutNumber, courierAccountId, courierName,
                    amountAriary, status, movementId, sellerPayoutNumberOverride, null, null, null);
        }
    }
    public record Balance(UUID courierAccountId, String courierName, long balanceAriary,
            long availableAriary, long reservedAriary) {}
    public record Batch(UUID id, UUID planId, LocalDate serviceDate, String status, long ledgerRevision,
            Instant expiresAt, String algorithm, List<Transfer> transfers, List<Balance> balances, String message,
            String mode, List<CourierTransfer> courierTransfers, int workflowVersion) {
        public Batch(UUID id, UUID planId, LocalDate serviceDate, String status, long ledgerRevision,
                Instant expiresAt, String algorithm, List<Transfer> transfers, List<Balance> balances,
                String message, String mode) {
            this(id, planId, serviceDate, status, ledgerRevision, expiresAt, algorithm, transfers, balances,
                    message, mode, List.of(), 1);
        }
    }
    public record CourierTransfer(UUID id, UUID fromCourierAccountId, String fromCourierName,
            UUID toCourierAccountId, String toCourierName, long amountAriary, String status,
            UUID movementId, boolean readyToConfirm, String blockedReason) {}
    public record BriefingTransferRow(UUID batchId, LocalDate serviceDate, String batchStatus,
            long ledgerRevision, String batchMessage, Transfer transfer, String kind,
            CourierTransfer courierTransfer) {
        public BriefingTransferRow(UUID batchId, LocalDate serviceDate, String batchStatus,
                long ledgerRevision, String batchMessage, Transfer transfer) {
            this(batchId, serviceDate, batchStatus, ledgerRevision, batchMessage, transfer, "SELLER_PAYOUT", null);
        }
    }
    public record PlanningSeller(UUID sellerAccountId, String sellerName, String sellerPayoutNumber,
            long balanceAriary, long plannedAriary, long availableAriary,
            List<ClientPayoutDestination> payoutDestinations) {
        public PlanningSeller(UUID sellerAccountId, String sellerName, String sellerPayoutNumber,
                long balanceAriary, long plannedAriary, long availableAriary) {
            this(sellerAccountId, sellerName, sellerPayoutNumber, balanceAriary, plannedAriary, availableAriary, List.of());
        }
    }
    public record PlanningCourier(UUID courierAccountId, String courierName, long balanceAriary,
            long reservedElsewhereAriary, long capacityAriary, long plannedAriary, long remainingAriary,
            long incomingAriary, long outgoingAriary) {
        public PlanningCourier(UUID courierAccountId, String courierName, long balanceAriary,
                long reservedElsewhereAriary, long capacityAriary, long plannedAriary, long remainingAriary) {
            this(courierAccountId, courierName, balanceAriary, reservedElsewhereAriary, capacityAriary,
                    plannedAriary, remainingAriary, 0, 0);
        }
    }
    public record Planning(UUID planId, LocalDate serviceDate, long ledgerRevision, Batch batch,
            List<PlanningSeller> sellers, List<PlanningCourier> couriers) {}

    /** Money must not be silently truncated by Jackson's permissive float-to-long coercion. */
    public static final class WholeAriaryDeserializer extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Long.class, "Le montant doit être un nombre entier d'ariary.");
            return parser.getLongValue();
        }
    }
}
