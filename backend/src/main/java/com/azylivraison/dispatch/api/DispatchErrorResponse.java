package com.azylivraison.dispatch.api;

import java.util.List;
import java.util.UUID;

public record DispatchErrorResponse(
        String code,
        String message,
        UUID dispatchPlanId,
        List<FieldViolation> errors) {

    public DispatchErrorResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    static DispatchErrorResponse of(String code, String message) {
        return new DispatchErrorResponse(code, message, null, List.of());
    }

    public record FieldViolation(String field, String message) {
    }
}
