package com.azylivraison.dailyworkflow.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.azylivraison.dailyworkflow.domain.DailyFinancialTotals;
import com.azylivraison.dailyworkflow.domain.DailyOperationDetails;
import com.azylivraison.dailyworkflow.domain.DailyPackageRecord;
import com.azylivraison.dailyworkflow.domain.OperationDayStatus;
import com.azylivraison.delivery.api.LocationResponse;
import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.delivery.domain.DeliveryPackageStatus;

public record DailyOperationResponse(
        LocalDate serviceDate,
        OperationDayStatus status,
        boolean admissionsClosed,
        UUID dispatchPlanId,
        int closureRevision,
        Instant closedAt,
        Instant reopenedAt,
        String reopenReason,
        long version,
        DayCountsResponse counts,
        FinancialTotalsResponse totals,
        List<ClientDaySummaryResponse> clients,
        List<DailyPackageResponse> packages) {

    public static DailyOperationResponse from(DailyOperationDetails details) {
        var day = details.day();
        List<DailyPackageRecord> packages = details.packages();
        return new DailyOperationResponse(
                day.serviceDate(),
                day.status(),
                day.admissionsClosedAt() != null,
                day.dispatchPlanId(),
                day.closureRevision(),
                day.closedAt(),
                day.reopenedAt(),
                day.reopenReason(),
                day.version(),
                DayCountsResponse.from(packages),
                FinancialTotalsResponse.from(details.totals()),
                clientSummaries(packages),
                packages.stream().map(DailyPackageResponse::from).toList());
    }

    private static List<ClientDaySummaryResponse> clientSummaries(List<DailyPackageRecord> packages) {
        Map<UUID, List<DailyPackageRecord>> grouped = new LinkedHashMap<>();
        for (DailyPackageRecord deliveryPackage : packages) {
            grouped.computeIfAbsent(deliveryPackage.ownerAccountId(), ignored -> new ArrayList<>())
                    .add(deliveryPackage);
        }
        return grouped.values().stream().map(ClientDaySummaryResponse::from).toList();
    }

    public record DayCountsResponse(
            int total,
            int finalized,
            int awaitingOutcome,
            int delivered,
            int cancelled,
            int postponed,
            int returnToSender,
            int returnsCompleted) {

        static DayCountsResponse from(List<DailyPackageRecord> packages) {
            return new DayCountsResponse(
                    packages.size(),
                    (int) packages.stream().filter(DailyPackageRecord::finalState).count(),
                    (int) packages.stream().filter(item -> !item.finalState()).count(),
                    count(packages, DeliveryPackageOutcome.DELIVERED),
                    count(packages, DeliveryPackageOutcome.CANCELLED),
                    count(packages, DeliveryPackageOutcome.POSTPONED),
                    count(packages, DeliveryPackageOutcome.RETURN_TO_SENDER),
                    (int) packages.stream().filter(item -> item.returnCompletedAt() != null).count());
        }

        private static int count(List<DailyPackageRecord> packages, DeliveryPackageOutcome outcome) {
            return (int) packages.stream().filter(item -> item.finalOutcome() == outcome).count();
        }
    }

    public record FinancialTotalsResponse(
            long recoveredAriary,
            long deliveryFeesAriary,
            long amountToRemitAriary) {

        static FinancialTotalsResponse from(DailyFinancialTotals totals) {
            return new FinancialTotalsResponse(
                    totals.recoveredAriary(), totals.deliveryFeesAriary(), totals.amountToRemitAriary());
        }
    }

    public record ClientDaySummaryResponse(
            UUID accountId,
            String displayName,
            String phone,
            int packageCount,
            int finalizedCount,
            FinancialTotalsResponse totals) {

        static ClientDaySummaryResponse from(List<DailyPackageRecord> packages) {
            DailyPackageRecord first = packages.getFirst();
            return new ClientDaySummaryResponse(
                    first.ownerAccountId(),
                    first.ownerDisplayName(),
                    first.ownerPhone(),
                    packages.size(),
                    (int) packages.stream().filter(DailyPackageRecord::finalState).count(),
                    FinancialTotalsResponse.from(DailyFinancialTotals.from(packages)));
        }
    }

    public record DailyPackageResponse(
            UUID packageId,
            UUID deliveryRequestId,
            UUID ownerAccountId,
            String ownerDisplayName,
            String ownerPhone,
            LocalDate requestedDate,
            LocationResponse pickup,
            int sequenceNumber,
            String recipientName,
            boolean faritra,
            LocationResponse destination,
            String contact,
            long amountToCollectAriary,
            boolean mobileMoneyPaid,
            UUID pricingZoneId,
            String pricingZoneName,
            Long deliveryFeeAriary,
            DeliveryPackageStatus technicalStatus,
            String unassignedReason,
            DeliveryPackageOutcome finalOutcome,
            Long actualCollectedAriary,
            String outcomeNote,
            Instant outcomeRecordedAt,
            Instant returnCompletedAt,
            UUID carriedForwardFromPackageId,
            UUID carriedForwardToPackageId,
            UUID dispatchPlanId,
            UUID distributorCourierId,
            String distributorCourierName,
            Instant estimatedDeliveryAt,
            boolean finalState,
            long recoveredAriary,
            long chargedDeliveryFeeAriary,
            long amountToRemitAriary,
            long version) {

        static DailyPackageResponse from(DailyPackageRecord deliveryPackage) {
            return new DailyPackageResponse(
                    deliveryPackage.packageId(), deliveryPackage.deliveryRequestId(),
                    deliveryPackage.ownerAccountId(), deliveryPackage.ownerDisplayName(),
                    deliveryPackage.ownerPhone(), deliveryPackage.requestedDate(),
                    LocationResponse.from(deliveryPackage.pickup()), deliveryPackage.sequenceNumber(),
                    deliveryPackage.recipientName(), deliveryPackage.faritra(),
                    LocationResponse.from(deliveryPackage.destination()), deliveryPackage.contact(),
                    deliveryPackage.amountToCollectAriary(), deliveryPackage.mobileMoneyPaid(),
                    deliveryPackage.pricingZoneId(), deliveryPackage.pricingZoneName(),
                    deliveryPackage.deliveryFeeAriary(), deliveryPackage.technicalStatus(),
                    deliveryPackage.unassignedReason(), deliveryPackage.finalOutcome(),
                    deliveryPackage.actualCollectedAriary(), deliveryPackage.outcomeNote(),
                    deliveryPackage.outcomeRecordedAt(), deliveryPackage.returnCompletedAt(),
                    deliveryPackage.carriedForwardFromPackageId(),
                    deliveryPackage.carriedForwardToPackageId(), deliveryPackage.dispatchPlanId(),
                    deliveryPackage.distributorCourierId(), deliveryPackage.distributorCourierName(),
                    deliveryPackage.estimatedDeliveryAt(), deliveryPackage.finalState(),
                    deliveryPackage.recoveredAriary(), deliveryPackage.chargedDeliveryFeeAriary(),
                    deliveryPackage.amountToRemitAriary(), deliveryPackage.version());
        }
    }
}
