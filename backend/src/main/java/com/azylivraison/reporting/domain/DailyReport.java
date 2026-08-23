package com.azylivraison.reporting.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, already calculated snapshot consumed by the daily report renderers.
 *
 * <p>The renderers deliberately do not infer any financial value from the parcel
 * outcome or from {@code mobileMoneyPaid}. In particular, Mobile Money is only an
 * informational flag. The producer of this model supplies the applied fee and
 * the amount to remit, which may legitimately be negative.</p>
 */
public record DailyReport(
        UUID reportId,
        LocalDate serviceDate,
        UUID dispatchPlanId,
        String hubLabel,
        int sourcePlanCount,
        Instant generatedAt,
        Closure closure,
        Totals totals,
        List<ClientSummary> clients,
        List<CourierSummary> couriers,
        List<PackageLine> packages) {

    public DailyReport {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(serviceDate, "serviceDate");
        Objects.requireNonNull(dispatchPlanId, "dispatchPlanId");
        requirePositive(sourcePlanCount, "sourcePlanCount");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(totals, "totals");
        clients = List.copyOf(Objects.requireNonNull(clients, "clients"));
        couriers = List.copyOf(Objects.requireNonNull(couriers, "couriers"));
        packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
    }

    public record Closure(
            Instant closedAt,
            UUID closedByAccountId,
            String closedByDisplayName,
            int version) {

        public Closure {
            Objects.requireNonNull(closedAt, "closedAt");
            Objects.requireNonNull(closedByAccountId, "closedByAccountId");
            requireNonNegative(version, "version");
        }
    }

    public record Totals(
            int packageCount,
            int deliveredCount,
            int cancelledCount,
            int postponedCount,
            int returnRequiredCount,
            int returnedToSenderCount,
            long amountCollectedAriary,
            long deliveryFeesAriary,
            long amountToRemitAriary) {

        public Totals {
            requireNonNegative(packageCount, "packageCount");
            requireNonNegative(deliveredCount, "deliveredCount");
            requireNonNegative(cancelledCount, "cancelledCount");
            requireNonNegative(postponedCount, "postponedCount");
            requireNonNegative(returnRequiredCount, "returnRequiredCount");
            requireNonNegative(returnedToSenderCount, "returnedToSenderCount");
            requireNonNegative(amountCollectedAriary, "amountCollectedAriary");
            requireNonNegative(deliveryFeesAriary, "deliveryFeesAriary");
            // amountToRemitAriary may be negative when a return still incurs a delivery fee.
        }
    }

    public record ClientSummary(
            UUID accountId,
            String displayName,
            String username,
            String phone,
            Totals totals) {

        public ClientSummary {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(totals, "totals");
        }
    }

    public record CourierSummary(
            UUID courierId,
            String displayName,
            String contact,
            String vehicleType,
            Totals totals) {

        public CourierSummary {
            Objects.requireNonNull(courierId, "courierId");
            Objects.requireNonNull(totals, "totals");
        }
    }

    public record PackageLine(
            UUID packageId,
            UUID deliveryRequestId,
            int sequenceNumber,
            UUID ownerAccountId,
            String clientDisplayName,
            String recipientName,
            String recipientContact,
            String pickupLabel,
            String destinationLabel,
            String pricingZoneName,
            PackageOutcome outcome,
            UUID plannedCourierId,
            String plannedCourierName,
            UUID actualCourierId,
            String actualCourierName,
            long expectedAmountToCollectAriary,
            long actualAmountCollectedAriary,
            long appliedDeliveryFeeAriary,
            long amountToRemitAriary,
            boolean mobileMoneyPaid,
            boolean faritra,
            Instant finalizedAt,
            String outcomeNote,
            boolean returnedToSender,
            Instant returnedToSenderAt,
            UUID replacementPackageId,
            LocalDate postponedToDate,
            String replacementPickupLabel) {

        public PackageLine {
            Objects.requireNonNull(packageId, "packageId");
            Objects.requireNonNull(deliveryRequestId, "deliveryRequestId");
            Objects.requireNonNull(ownerAccountId, "ownerAccountId");
            Objects.requireNonNull(outcome, "outcome");
            requirePositive(sequenceNumber, "sequenceNumber");
            requireNonNegative(expectedAmountToCollectAriary, "expectedAmountToCollectAriary");
            requireNonNegative(actualAmountCollectedAriary, "actualAmountCollectedAriary");
            requireNonNegative(appliedDeliveryFeeAriary, "appliedDeliveryFeeAriary");
            // amountToRemitAriary may be negative and is intentionally not recalculated here.
        }
    }

    public enum PackageOutcome {
        DELIVERED("Livré"),
        CANCELLED("Annulé"),
        POSTPONED("Reporté"),
        RETURN_TO_SENDER("À retourner");

        private final String frenchLabel;

        PackageOutcome(String frenchLabel) {
            this.frenchLabel = frenchLabel;
        }

        public String frenchLabel() {
            return frenchLabel;
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
    }

    private static void requirePositive(int value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    }
}
