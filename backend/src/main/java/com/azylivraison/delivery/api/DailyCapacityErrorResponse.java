package com.azylivraison.delivery.api;

import java.time.LocalDate;

public record DailyCapacityErrorResponse(
        String code,
        String message,
        LocalDate requestedDate,
        int limit,
        int current,
        int requested,
        int projected,
        int remaining,
        int excess) {
}
