package com.azylivraison.dispatch.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.dispatch.domain.DispatchPlanRecord;
import com.azylivraison.dispatch.domain.DispatchStatus;
import com.azylivraison.dispatch.domain.DispatchMode;
import com.azylivraison.planning.domain.Location;

public record DispatchPlanSummaryResponse(
        UUID id,
        LocalDate serviceDate,
        DispatchStatus status,
        int revision,
        DispatchMode mode,
        long version,
        UUID supersedesPlanId,
        boolean editable,
        String strategy,
        Location hub,
        int selectedPackageCount,
        Integer assignedPackageCount,
        Integer unassignedPackageCount,
        int selectedCourierCount,
        Integer usedCourierCount,
        Long totalDistanceMeters,
        Long totalDurationSeconds,
        Instant meetingAt,
        Instant redistributionCompletedAt,
        Instant completedAt,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant completedProcessingAt,
        Instant archivedAt,
        UUID archivedByAccountId,
        String archiveReason) {

    static DispatchPlanSummaryResponse from(DispatchPlanRecord plan, boolean editable) {
        return new DispatchPlanSummaryResponse(
                plan.id(),
                plan.serviceDate(),
                plan.status(),
                plan.revision(),
                plan.mode(),
                plan.version(),
                plan.supersedesPlanId(),
                editable,
                plan.mode() == DispatchMode.MANUAL && plan.resultJson() == null ? "MANUAL" : "VIA_HUB",
                plan.hub(),
                plan.selectedPackageCount(),
                plan.assignedPackageCount(),
                plan.unassignedPackageCount(),
                plan.selectedCourierCount(),
                plan.usedCourierCount(),
                plan.totalDistanceMeters(),
                plan.totalDurationSeconds(),
                plan.meetingAt(),
                plan.redistributionCompletedAt(),
                plan.completedAt(),
                plan.failureCode(),
                plan.failureMessage(),
                plan.createdAt(),
                plan.completedProcessingAt(),
                plan.archivedAt(),
                plan.archivedByAccountId(),
                plan.archiveReason());
    }
}
