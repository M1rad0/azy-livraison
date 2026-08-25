package com.azylivraison.dailyworkflow.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record OperationDay(
        LocalDate serviceDate,
        OperationDayStatus status,
        Instant admissionsClosedAt,
        UUID dispatchPlanId,
        int closureRevision,
        Instant closedAt,
        UUID closedByAccountId,
        Instant reopenedAt,
        UUID reopenedByAccountId,
        String reopenReason,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static OperationDay open(LocalDate serviceDate) {
        return new OperationDay(
                serviceDate, OperationDayStatus.OPEN, null, null, 0,
                null, null, null, null, null, 0, null, null);
    }
}
