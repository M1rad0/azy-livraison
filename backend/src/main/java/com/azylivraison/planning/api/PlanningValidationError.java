package com.azylivraison.planning.api;

public record PlanningValidationError(
        String path,
        String code,
        String message) {
}

