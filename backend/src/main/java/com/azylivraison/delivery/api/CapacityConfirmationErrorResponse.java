package com.azylivraison.delivery.api;

public record CapacityConfirmationErrorResponse(
        String code,
        String message,
        int limit,
        int current,
        int requested,
        int projected) {
}
