package com.azylivraison.dailyworkflow.api;

import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.dailyworkflow.repository.DailyWorkflowRepository.CarryForwardIds;

public record CarryForwardResponse(
        UUID deliveryRequestId,
        UUID packageId,
        LocalDate requestedDate) {

    static CarryForwardResponse from(CarryForwardIds ids) {
        return new CarryForwardResponse(ids.requestId(), ids.packageId(), ids.requestedDate());
    }
}
