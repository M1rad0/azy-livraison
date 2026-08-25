package com.azylivraison.dispatch.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.dispatch.domain.DispatchPlanRecord;
import com.azylivraison.dispatch.domain.DispatchStatus;
import com.azylivraison.planning.domain.Location;

public record DispatchPlanSummaryResponse(
        UUID id,
        LocalDate serviceDate,
        DispatchStatus status,
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
        Instant completedProcessingAt) {

    static DispatchPlanSummaryResponse from(DispatchPlanRecord plan) {
        return new DispatchPlanSummaryResponse(
                plan.id(),
                plan.serviceDate(),
                plan.status(),
                "VIA_HUB",
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
                plan.completedProcessingAt());
    }
}
