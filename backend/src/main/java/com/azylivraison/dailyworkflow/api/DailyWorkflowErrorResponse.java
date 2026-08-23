package com.azylivraison.dailyworkflow.api;

import java.util.List;

public record DailyWorkflowErrorResponse(
        String code,
        String message,
        List<FieldViolation> violations) {

    public static DailyWorkflowErrorResponse of(String code, String message) {
        return new DailyWorkflowErrorResponse(code, message, List.of());
    }

    public record FieldViolation(String field, String message) {
    }
}
