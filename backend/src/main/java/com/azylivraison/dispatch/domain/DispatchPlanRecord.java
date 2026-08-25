package com.azylivraison.dispatch.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.planning.domain.Location;

public record DispatchPlanRecord(
        UUID id,
        LocalDate serviceDate,
        DispatchStatus status,
        int revision,
        DispatchMode mode,
        long version,
        UUID supersedesPlanId,
        UUID createdByAccountId,
        String idempotencyKey,
        String requestHash,
        Location hub,
        String inputSnapshotJson,
        String policySnapshotJson,
        String resultJson,
        String hubExecutionJson,
        String metricsJson,
        String resultStatus,
        String solverStatus,
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
        String archiveReason,
        Instant updatedAt) {

    public boolean editable() {
        return status == DispatchStatus.READY || status == DispatchStatus.READY_WITH_WARNINGS;
    }
}
