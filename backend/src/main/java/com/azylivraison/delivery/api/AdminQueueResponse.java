package com.azylivraison.delivery.api;

import java.time.LocalDate;
import java.util.List;

public record AdminQueueResponse(
        LocalDate date,
        AdminQueueStatsResponse stats,
        List<DeliveryRequestResponse> requests) {

    public AdminQueueResponse {
        requests = List.copyOf(requests);
    }
}
