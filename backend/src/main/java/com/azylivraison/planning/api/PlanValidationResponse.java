package com.azylivraison.planning.api;

import java.util.List;

import com.azylivraison.planning.domain.DeliveryPlan;

public record PlanValidationResponse(
        boolean valid,
        DeliveryPlan plan,
        List<PlanningValidationError> errors) {

    public static PlanValidationResponse success(DeliveryPlan plan) {
        return new PlanValidationResponse(true, plan, List.of());
    }

    public static PlanValidationResponse failure(List<PlanningValidationError> errors) {
        return new PlanValidationResponse(false, null, List.copyOf(errors));
    }
}

