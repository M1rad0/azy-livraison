package com.azylivraison.finance.cash.briefing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
            @Size(max = 100) String sellerPayoutNumber) {
        public ManualTransfer(UUID sellerAccountId, UUID courierAccountId, long amountAriary) {
            this(sellerAccountId, courierAccountId, amountAriary, null);
        }
    }
    public record ManualRequest(@NotNull UUID planId, @NotBlank @Size(max = 100) String idempotencyKey,
            @NotNull Long expectedLedgerRevision, UUID expectedBatchId,
            @NotNull @Size(max = 1000) List<@Valid ManualTransfer> transfers) {}
    public record ConfirmRequest(@NotBlank String paymentMethod, String reference, String note,
            @NotNull Long expectedLedgerRevision) {}
    public record Transfer(UUID id, UUID sellerAccountId, String sellerName, String sellerPayoutNumber,
            UUID courierAccountId, String courierName, long amountAriary, String status, UUID movementId,
            boolean sellerPayoutNumberOverride) {
        public Transfer(UUID id, UUID sellerAccountId, String sellerName, String sellerPayoutNumber,
                UUID courierAccountId, String courierName, long amountAriary, String status, UUID movementId) {
            this(id, sellerAccountId, sellerName, sellerPayoutNumber, courierAccountId, courierName,
                    amountAriary, status, movementId, false);
        }
    }
    public record Balance(UUID courierAccountId, String courierName, long balanceAriary,
            long availableAriary, long reservedAriary) {}
    public record Batch(UUID id, UUID planId, LocalDate serviceDate, String status, long ledgerRevision,
            Instant expiresAt, String algorithm, List<Transfer> transfers, List<Balance> balances, String message,
            String mode) {}
    public record PlanningSeller(UUID sellerAccountId, String sellerName, String sellerPayoutNumber,
            long balanceAriary, long plannedAriary, long availableAriary) {}
    public record PlanningCourier(UUID courierAccountId, String courierName, long balanceAriary,
            long reservedElsewhereAriary, long capacityAriary, long plannedAriary, long remainingAriary) {}
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
