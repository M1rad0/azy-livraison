package com.azylivraison.dispatch.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DispatchCourierResult(
        UUID courierId,
        boolean used,
        boolean collectionRole,
        boolean distributionRole,
        Instant hubArrivalAt,
        Instant hubDepartureAt,
        Instant completedAt,
        Long waitingSeconds,
        BigDecimal loadAtHubArrivalKg,
        BigDecimal loadAtHubDepartureKg,
        Long routeDistanceMeters,
        Long routeDurationSeconds) {
}
