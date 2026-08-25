package com.azylivraison.delivery.api;

public record AdminQueueStatsResponse(
        int dailyLimit,
        int pendingRequestCount,
        int partiallyDispatchedRequestCount,
        int pendingPackageCount,
        int remainingCapacity,
        boolean capacityExceeded) {
}
