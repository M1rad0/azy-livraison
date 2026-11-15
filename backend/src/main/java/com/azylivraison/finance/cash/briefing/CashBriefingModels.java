package com.azylivraison.finance.cash.briefing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CashBriefingModels {
    private CashBriefingModels() {}

    public record PrepareRequest(@NotNull UUID planId, @NotBlank @Size(max = 100) String idempotencyKey) {}
    public record ConfirmRequest(@NotBlank String paymentMethod, String reference, String note,
            @NotNull Long expectedLedgerRevision) {}
    public record Transfer(UUID id, UUID sellerAccountId, String sellerName, String sellerPayoutNumber,
            UUID courierAccountId, String courierName, long amountAriary, String status, UUID movementId) {}
    public record Balance(UUID courierAccountId, String courierName, long balanceAriary,
            long availableAriary, long reservedAriary) {}
    public record Batch(UUID id, UUID planId, LocalDate serviceDate, String status, long ledgerRevision,
            Instant expiresAt, String algorithm, List<Transfer> transfers, List<Balance> balances, String message) {}
}
