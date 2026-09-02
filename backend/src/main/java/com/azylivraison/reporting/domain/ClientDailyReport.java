package com.azylivraison.reporting.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.azylivraison.reporting.domain.DailyReport.PackageOutcome;
import com.azylivraison.reporting.domain.DailyReport.Totals;

/**
 * Client-facing projection of a closed daily report.
 *
 * <p>This contract intentionally excludes dispatch, courier, closure and timing
 * metadata. Those values remain available in the internal {@link DailyReport}
 * snapshot for operations and audit purposes.</p>
 */
public record ClientDailyReport(
        UUID reportId,
        LocalDate serviceDate,
        String clientDisplayName,
        String clientPhone,
        Totals totals,
        List<PackageLine> packages) {

    public ClientDailyReport {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(serviceDate, "serviceDate");
        Objects.requireNonNull(clientDisplayName, "clientDisplayName");
        Objects.requireNonNull(totals, "totals");
        packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
    }

    /** Only the operational and financial parcel values useful to the client. */
    public record PackageLine(
            UUID packageId,
            String recipientName,
            String recipientContact,
            String pickupLabel,
            String destinationLabel,
            PackageOutcome outcome,
            String comment,
            long actualAmountCollectedAriary,
            long appliedDeliveryFeeAriary,
            long amountToRemitAriary,
            boolean mobileMoneyPaid,
            boolean returnedToSender) {

        public PackageLine {
            Objects.requireNonNull(packageId, "packageId");
            Objects.requireNonNull(outcome, "outcome");
            if (actualAmountCollectedAriary < 0) {
                throw new IllegalArgumentException("actualAmountCollectedAriary must not be negative");
            }
            if (appliedDeliveryFeeAriary < 0) {
                throw new IllegalArgumentException("appliedDeliveryFeeAriary must not be negative");
            }
            // amountToRemitAriary may be negative when a return still incurs a fee.
        }
    }
}
