package com.azylivraison.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.delivery.domain.ExpressFuelCalculationStatus;
import com.azylivraison.delivery.domain.ExpressFuelCalculation;

/** Domain snapshots used by the finance services and their HTTP adapters. */
public final class FinanceModels {

    private FinanceModels() {
    }

    public enum ClientPaymentDirection {
        AZY_TO_CLIENT(1),
        CLIENT_TO_AZY(-1);

        private final int sign;

        ClientPaymentDirection(int sign) {
            this.sign = sign;
        }

        public long signed(long amountAriary) {
            return Math.multiplyExact(amountAriary, sign);
        }
    }

    public enum PaymentStatus {
        CONFIRMED,
        VOIDED
    }

    public enum SettlementStatus {
        UNPAID,
        PARTIALLY_PAID,
        PAID
    }

    public record PaymentDestination(
            String operator,
            String accountHolder,
            String number) {
    }

    public record ProofMetadata(
            String storageKey,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String sha256) {
    }

    public record ProofDownload(
            byte[] content,
            String contentType,
            String originalFilename) {

        public ProofDownload {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public record ClientDailySettlement(
            UUID id,
            UUID reportId,
            LocalDate serviceDate,
            int closureRevision,
            UUID clientAccountId,
            String clientDisplayName,
            long amountDueAriary,
            long confirmedSignedPaymentsAriary,
            Instant createdAt) {

        public long remainingAriary() {
            return Math.subtractExact(amountDueAriary, confirmedSignedPaymentsAriary);
        }

        public SettlementStatus status() {
            if (amountDueAriary == 0) return SettlementStatus.PAID;
            if (confirmedSignedPaymentsAriary == 0
                    || Long.signum(confirmedSignedPaymentsAriary) != Long.signum(amountDueAriary)) {
                return SettlementStatus.UNPAID;
            }
            boolean fullyPaid = amountDueAriary > 0
                    ? confirmedSignedPaymentsAriary >= amountDueAriary
                    : confirmedSignedPaymentsAriary <= amountDueAriary;
            return fullyPaid ? SettlementStatus.PAID : SettlementStatus.PARTIALLY_PAID;
        }
    }

    public record ClientPayment(
            UUID id,
            UUID settlementId,
            UUID clientAccountId,
            LocalDate serviceDate,
            ClientPaymentDirection direction,
            long amountAriary,
            String paymentMethod,
            PaymentDestination destination,
            String transactionReference,
            String note,
            ProofMetadata proof,
            PaymentStatus status,
            Instant confirmedAt,
            UUID confirmedByAccountId,
            Instant voidedAt,
            UUID voidedByAccountId,
            String voidReason,
            long version) {

        public long signedAmountAriary() {
            return direction.signed(amountAriary);
        }
    }

    public record ClientFinance(
            UUID clientAccountId,
            PaymentDestination preferredPayoutDestination,
            long totalDueAriary,
            long totalConfirmedSignedPaymentsAriary,
            long netBalanceAriary,
            long amountOwedToClientAriary,
            long amountOwedByClientAriary,
            List<ClientDailySettlement> settlements,
            List<ClientPayment> payments) {

        public ClientFinance {
            settlements = List.copyOf(settlements);
            payments = List.copyOf(payments);
        }
    }

    public record CompensationSourceLine(
            UUID packageId,
            UUID courierId,
            String courierName,
            String courierContact,
            DeliveryPackageOutcome outcome,
            Instant returnCompletedAt,
            long deliveryFeeAriary,
            int commissionNumerator,
            int commissionDenominator,
            String recipientName,
            String destinationLabel) {

        public boolean eligible() {
            return outcome == DeliveryPackageOutcome.DELIVERED
                    || (outcome == DeliveryPackageOutcome.RETURN_TO_SENDER && returnCompletedAt != null);
        }
    }

    public record CompensationLine(
            UUID earningId,
            UUID packageId,
            UUID courierId,
            String courierName,
            String courierContact,
            DeliveryPackageOutcome outcome,
            boolean eligible,
            String ineligibilityReason,
            long deliveryFeeAriary,
            int commissionNumerator,
            int commissionDenominator,
            long suggestedAmountAriary,
            Long finalAmountAriary,
            String overrideReason,
            String recipientName,
            String destinationLabel) {
    }

    public record CourierCompensationPreview(
            LocalDate serviceDate,
            int closureRevision,
            boolean finalized,
            Instant finalizedAt,
            UUID finalizedByAccountId,
            long totalSuggestedAriary,
            long totalFinalAriary,
            long totalSuggestedCommissionAriary,
            long totalFinalCommissionAriary,
            long totalSuggestedFuelAriary,
            long totalFinalFuelAriary,
            int pendingReturnCount,
            int unresolvedFuelCalculationCount,
            List<CompensationLine> lines,
            List<FuelCompensationLine> fuelLines,
            List<ExpressFuelCalculation> fuelCalculations) {

        public CourierCompensationPreview {
            lines = List.copyOf(lines);
            fuelLines = List.copyOf(fuelLines);
            fuelCalculations = List.copyOf(fuelCalculations);
        }
    }

    public record FuelCompensationLine(
            UUID requestId,
            String clientDisplayName,
            String pickupLabel,
            ExpressFuelCalculationStatus status,
            String failureReason,
            UUID courierId,
            String courierName,
            String courierContact,
            List<UUID> packageIds,
            boolean returnToPickup,
            Long repositionDistanceMeters,
            Long automaticDistanceMeters,
            long finalDistanceMeters,
            boolean fuelReimbursementEnabled,
            BigDecimal fuelEfficiencyKmPerLiter,
            long fuelPricePerLiterAriary,
            long suggestedAmountAriary,
            long finalAmountAriary,
            String overrideReason,
            boolean manual) {

        public FuelCompensationLine {
            packageIds = List.copyOf(packageIds == null ? List.of() : packageIds);
        }
    }

    public record CompensationOverride(
            UUID packageId,
            long finalAmountAriary,
            String reason) {
    }

    public record CourierEarning(
            UUID id,
            LocalDate serviceDate,
            int closureRevision,
            UUID packageId,
            UUID courierId,
            String courierName,
            String courierContact,
            DeliveryPackageOutcome outcome,
            long deliveryFeeAriary,
            int commissionNumerator,
            int commissionDenominator,
            long suggestedAmountAriary,
            long finalAmountAriary,
            String overrideReason,
            Instant createdAt) {
    }

    public record CourierFuelEarning(
            UUID id,
            LocalDate serviceDate,
            int closureRevision,
            UUID requestId,
            UUID courierId,
            String courierName,
            String courierContact,
            boolean returnToPickup,
            List<UUID> packageIds,
            Long repositionDistanceMeters,
            Long automaticDistanceMeters,
            long finalDistanceMeters,
            boolean fuelReimbursementEnabled,
            BigDecimal fuelEfficiencyKmPerLiter,
            long fuelPricePerLiterAriary,
            long suggestedAmountAriary,
            long finalAmountAriary,
            String overrideReason,
            boolean manual,
            Instant createdAt) {

        public CourierFuelEarning {
            packageIds = List.copyOf(packageIds == null ? List.of() : packageIds);
        }
    }

    public record CourierPayment(
            UUID id,
            UUID courierId,
            long amountAriary,
            String paymentMethod,
            PaymentDestination destination,
            String transactionReference,
            String note,
            ProofMetadata proof,
            PaymentStatus status,
            Instant confirmedAt,
            UUID confirmedByAccountId,
            Instant voidedAt,
            UUID voidedByAccountId,
            String voidReason,
            long version) {
    }

    public record CourierDelivery(
            UUID packageId,
            LocalDate serviceDate,
            String clientDisplayName,
            String recipientName,
            String pickupLabel,
            String destinationLabel,
            DeliveryPackageOutcome outcome,
            Instant returnCompletedAt,
            long deliveryFeeAriary,
            Long earnedAmountAriary,
            Long fuelEarnedAmountAriary,
            Long totalEarnedAmountAriary) {

        public CourierDelivery(UUID packageId, LocalDate serviceDate, String clientDisplayName,
                String recipientName, String pickupLabel, String destinationLabel,
                DeliveryPackageOutcome outcome, Instant returnCompletedAt,
                long deliveryFeeAriary, Long earnedAmountAriary) {
            this(packageId, serviceDate, clientDisplayName, recipientName, pickupLabel,
                    destinationLabel, outcome, returnCompletedAt, deliveryFeeAriary,
                    earnedAmountAriary, null, earnedAmountAriary);
        }
    }

    public record CourierFinance(
            UUID courierId,
            String courierName,
            long totalCommissionEarnedAriary,
            long totalFuelEarnedAriary,
            long totalEarnedAriary,
            long totalPaidAriary,
            long remainingToPayAriary,
            long advanceCreditAriary,
            List<CourierDelivery> deliveries,
            List<CourierEarning> earnings,
            List<CourierFuelEarning> fuelEarnings,
            List<CourierPayment> payments) {

        public CourierFinance {
            deliveries = List.copyOf(deliveries);
            earnings = List.copyOf(earnings);
            fuelEarnings = List.copyOf(fuelEarnings);
            payments = List.copyOf(payments);
        }

        public CourierFinance(UUID courierId, String courierName, long totalEarnedAriary,
                long totalPaidAriary, long remainingToPayAriary, long advanceCreditAriary,
                List<CourierDelivery> deliveries, List<CourierEarning> earnings,
                List<CourierPayment> payments) {
            this(courierId, courierName, totalEarnedAriary, 0, totalEarnedAriary,
                    totalPaidAriary, remainingToPayAriary, advanceCreditAriary,
                    deliveries, earnings, List.of(), payments);
        }
    }

    public record ClosedDay(LocalDate serviceDate, String status, int closureRevision) {
    }

    public record ClosedClient(
            LocalDate serviceDate,
            UUID clientAccountId,
            int closureRevision,
            UUID reportId,
            Instant closedAt) {
    }

    public record CompensationBatch(
            LocalDate serviceDate,
            int closureRevision,
            long totalSuggestedAriary,
            long totalFinalAriary,
            long totalSuggestedCommissionAriary,
            long totalFinalCommissionAriary,
            long totalSuggestedFuelAriary,
            long totalFinalFuelAriary,
            Instant finalizedAt,
            UUID finalizedByAccountId,
            long version,
            UUID clientAccountId) {
    }
}
