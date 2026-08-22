package com.azylivraison.delivery.api;

public record CapacitySnapshotResponse(
        int limit,
        int current,
        int requested,
        int projected,
        boolean warning,
        boolean accepted) {
}
