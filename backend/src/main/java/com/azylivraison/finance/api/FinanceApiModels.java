package com.azylivraison.finance.api;

import static com.azylivraison.finance.domain.FinanceModels.*;

import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.delivery.domain.ExpressFuelCalculationStatus;
import com.azylivraison.delivery.api.ExpressFuelResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class FinanceApiModels {

    private FinanceApiModels() {
    }

    public record PaymentDestinationRequest(
            @NotBlank @Size(max = 80) String operator,
            @NotBlank @Size(max = 150) String accountHolder,
            @NotBlank @Size(max = 100) String number) {

        PaymentDestination toDomain() {
            return new PaymentDestination(operator, accountHolder, number);
        }
    }

    public record CreateClientPaymentRequest(
            @NotNull ClientPaymentDirection direction,
            @NotNull @Positive Long amountAriary,
            @NotBlank @Size(max = 80) String paymentMethod,
            @Valid PaymentDestinationRequest destination,
            @Size(max = 200) String transactionReference,
            @Size(max = 1000) String note,
            Instant confirmedAt) {
    }

    public record CreateCourierPaymentRequest(
            @NotNull @Positive Long amountAriary,
            @NotBlank @Size(max = 80) String paymentMethod,
            @Valid PaymentDestinationRequest destination,
            @Size(max = 200) String transactionReference,
            @Size(max = 1000) String note,
            Instant confirmedAt) {
    }

    public record VoidPaymentRequest(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record CompensationOverrideRequest(
            @NotNull UUID packageId,
            @NotNull @PositiveOrZero Long finalAmountAriary,
            @Size(max = 1000) String reason) {

        CompensationOverride toDomain() {
            return new CompensationOverride(packageId, finalAmountAriary, reason);
        }
    }

    public record FinalizeCompensationsRequest(
            @NotNull @Positive Integer closureRevision,
            List<@Valid CompensationOverrideRequest> overrides) {
    }

    public record ProofResponse(
            boolean available,
            String originalFilename,
            String contentType,
            Long sizeBytes,
            String sha256) {

        static ProofResponse from(ProofMetadata proof) {
            return proof == null
                    ? new ProofResponse(false, null, null, null, null)
                    : new ProofResponse(true, proof.originalFilename(), proof.contentType(),
                            proof.sizeBytes(), proof.sha256());
        }
    }

    public record ClientSettlementResponse(
            UUID id,
            UUID reportId,
            LocalDate serviceDate,
            int closureRevision,
            String clientDisplayName,
            long amountDueAriary,
            long confirmedSignedPaymentsAriary,
            long remainingAriary,
            SettlementStatus status,
            Instant createdAt,
            boolean cashLedgerManaged) {

        static ClientSettlementResponse from(ClientDailySettlement settlement) {
            return from(settlement, false);
        }

        static ClientSettlementResponse from(ClientDailySettlement settlement, boolean cashLedgerManaged) {
            return new ClientSettlementResponse(
                    settlement.id(), settlement.reportId(), settlement.serviceDate(),
                    settlement.closureRevision(), settlement.clientDisplayName(),
                    settlement.amountDueAriary(), settlement.confirmedSignedPaymentsAriary(),
                    settlement.remainingAriary(), settlement.status(), settlement.createdAt(), cashLedgerManaged);
        }
    }

    public record ClientPaymentResponse(
            UUID id,
            UUID settlementId,
            LocalDate serviceDate,
            ClientPaymentDirection direction,
            long amountAriary,
            long signedAmountAriary,
            String paymentMethod,
            PaymentDestination destination,
            String transactionReference,
            String note,
            ProofResponse proof,
            PaymentStatus status,
            Instant confirmedAt,
            UUID confirmedByAccountId,
            Instant voidedAt,
            UUID voidedByAccountId,
            String voidReason,
            long version) {

        static ClientPaymentResponse from(ClientPayment payment) {
            return new ClientPaymentResponse(
                    payment.id(), payment.settlementId(), payment.serviceDate(), payment.direction(),
                    payment.amountAriary(), payment.signedAmountAriary(), payment.paymentMethod(),
                    payment.destination(), payment.transactionReference(), payment.note(),
                    ProofResponse.from(payment.proof()), payment.status(), payment.confirmedAt(),
                    payment.confirmedByAccountId(), payment.voidedAt(), payment.voidedByAccountId(),
                    payment.voidReason(), payment.version());
        }
    }

    public record ClientFinanceResponse(
            UUID clientAccountId,
            PaymentDestination preferredPayoutDestination,
            long totalDueAriary,
            long totalConfirmedSignedPaymentsAriary,
            long netBalanceAriary,
            long amountOwedToClientAriary,
            long amountOwedByClientAriary,
            List<ClientSettlementResponse> settlements,
            List<ClientPaymentResponse> payments) {

        static ClientFinanceResponse from(ClientFinance finance) {
            return from(finance, Set.of());
        }

        static ClientFinanceResponse from(ClientFinance finance, Set<UUID> cashLedgerManaged) {
            return new ClientFinanceResponse(
                    finance.clientAccountId(), finance.preferredPayoutDestination(), finance.totalDueAriary(),
                    finance.totalConfirmedSignedPaymentsAriary(), finance.netBalanceAriary(),
                    finance.amountOwedToClientAriary(), finance.amountOwedByClientAriary(),
                    finance.settlements().stream().map(settlement -> ClientSettlementResponse.from(
                            settlement, cashLedgerManaged.contains(settlement.id()))).toList(),
                    finance.payments().stream().map(ClientPaymentResponse::from).toList());
        }
    }

    public record CompensationLineResponse(
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

        static CompensationLineResponse from(CompensationLine line) {
            return new CompensationLineResponse(
                    line.earningId(), line.packageId(), line.courierId(), line.courierName(),
                    line.courierContact(), line.outcome(), line.eligible(), line.ineligibilityReason(),
                    line.deliveryFeeAriary(), line.commissionNumerator(), line.commissionDenominator(),
                    line.suggestedAmountAriary(), line.finalAmountAriary(), line.overrideReason(),
                    line.recipientName(), line.destinationLabel());
        }
    }

    public record CourierCompensationResponse(
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
            List<CompensationLineResponse> lines,
            List<FuelCompensationLineResponse> fuelLines,
            List<ExpressFuelResponse> fuelCalculations,
            boolean newAccrualsEnabled) {

        static CourierCompensationResponse from(CourierCompensationPreview preview, boolean newAccrualsEnabled) {
            return new CourierCompensationResponse(
                    preview.serviceDate(), preview.closureRevision(), preview.finalized(),
                    preview.finalizedAt(), preview.finalizedByAccountId(),
                    preview.totalSuggestedAriary(), preview.totalFinalAriary(),
                    preview.totalSuggestedCommissionAriary(), preview.totalFinalCommissionAriary(),
                    preview.totalSuggestedFuelAriary(), preview.totalFinalFuelAriary(),
                    preview.pendingReturnCount(), preview.unresolvedFuelCalculationCount(),
                    preview.lines().stream().map(CompensationLineResponse::from).toList(),
                    preview.fuelLines().stream().map(FuelCompensationLineResponse::from).toList(),
                    preview.fuelCalculations().stream().map(ExpressFuelResponse::from).toList(), newAccrualsEnabled);
        }
    }

    public record FuelCompensationLineResponse(
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

        static FuelCompensationLineResponse from(FuelCompensationLine line) {
            return new FuelCompensationLineResponse(
                    line.requestId(), line.clientDisplayName(), line.pickupLabel(), line.status(),
                    line.failureReason(), line.courierId(), line.courierName(), line.courierContact(),
                    line.packageIds(), line.returnToPickup(), line.repositionDistanceMeters(),
                    line.automaticDistanceMeters(), line.finalDistanceMeters(), line.fuelReimbursementEnabled(),
                    line.fuelEfficiencyKmPerLiter(), line.fuelPricePerLiterAriary(),
                    line.suggestedAmountAriary(), line.finalAmountAriary(), line.overrideReason(),
                    line.manual());
        }
    }

    public record CourierEarningResponse(
            UUID id,
            LocalDate serviceDate,
            int closureRevision,
            UUID packageId,
            DeliveryPackageOutcome outcome,
            long deliveryFeeAriary,
            int commissionNumerator,
            int commissionDenominator,
            long suggestedAmountAriary,
            long finalAmountAriary,
            String overrideReason,
            Instant createdAt) {

        static CourierEarningResponse from(CourierEarning earning) {
            return new CourierEarningResponse(
                    earning.id(), earning.serviceDate(), earning.closureRevision(), earning.packageId(),
                    earning.outcome(), earning.deliveryFeeAriary(), earning.commissionNumerator(),
                    earning.commissionDenominator(), earning.suggestedAmountAriary(),
                    earning.finalAmountAriary(), earning.overrideReason(), earning.createdAt());
        }
    }

    public record CourierPaymentResponse(
            UUID id,
            long amountAriary,
            String paymentMethod,
            PaymentDestination destination,
            String transactionReference,
            String note,
            ProofResponse proof,
            PaymentStatus status,
            Instant confirmedAt,
            UUID confirmedByAccountId,
            Instant voidedAt,
            UUID voidedByAccountId,
            String voidReason,
            long version) {

        static CourierPaymentResponse from(CourierPayment payment) {
            return new CourierPaymentResponse(
                    payment.id(), payment.amountAriary(), payment.paymentMethod(), payment.destination(),
                    payment.transactionReference(), payment.note(), ProofResponse.from(payment.proof()),
                    payment.status(), payment.confirmedAt(), payment.confirmedByAccountId(),
                    payment.voidedAt(), payment.voidedByAccountId(), payment.voidReason(), payment.version());
        }
    }

    public record CourierFuelEarningResponse(
            UUID id,
            LocalDate serviceDate,
            int closureRevision,
            UUID requestId,
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

        static CourierFuelEarningResponse from(CourierFuelEarning earning) {
            return new CourierFuelEarningResponse(
                    earning.id(), earning.serviceDate(), earning.closureRevision(), earning.requestId(),
                    earning.returnToPickup(), earning.packageIds(), earning.repositionDistanceMeters(),
                    earning.automaticDistanceMeters(), earning.finalDistanceMeters(), earning.fuelReimbursementEnabled(),
                    earning.fuelEfficiencyKmPerLiter(), earning.fuelPricePerLiterAriary(),
                    earning.suggestedAmountAriary(), earning.finalAmountAriary(), earning.overrideReason(),
                    earning.manual(), earning.createdAt());
        }
    }

    public record CourierDeliveryResponse(
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

        static CourierDeliveryResponse from(CourierDelivery delivery) {
            return new CourierDeliveryResponse(
                    delivery.packageId(), delivery.serviceDate(), delivery.clientDisplayName(),
                    delivery.recipientName(), delivery.pickupLabel(), delivery.destinationLabel(),
                    delivery.outcome(), delivery.returnCompletedAt(), delivery.deliveryFeeAriary(),
                    delivery.earnedAmountAriary(), delivery.fuelEarnedAmountAriary(),
                    delivery.totalEarnedAmountAriary());
        }
    }

    public record CourierFinanceResponse(
            UUID courierId,
            String courierName,
            long totalCommissionEarnedAriary,
            long totalFuelEarnedAriary,
            long totalEarnedAriary,
            long totalPaidAriary,
            long remainingToPayAriary,
            long advanceCreditAriary,
            List<CourierDeliveryResponse> deliveries,
            List<CourierEarningResponse> earnings,
            List<CourierFuelEarningResponse> fuelEarnings,
            List<CourierPaymentResponse> payments) {

        static CourierFinanceResponse from(CourierFinance finance) {
            return new CourierFinanceResponse(
                    finance.courierId(), finance.courierName(), finance.totalCommissionEarnedAriary(),
                    finance.totalFuelEarnedAriary(), finance.totalEarnedAriary(),
                    finance.totalPaidAriary(), finance.remainingToPayAriary(), finance.advanceCreditAriary(),
                    finance.deliveries().stream().map(CourierDeliveryResponse::from).toList(),
                    finance.earnings().stream().map(CourierEarningResponse::from).toList(),
                    finance.fuelEarnings().stream().map(CourierFuelEarningResponse::from).toList(),
                    finance.payments().stream().map(CourierPaymentResponse::from).toList());
        }
    }
}
