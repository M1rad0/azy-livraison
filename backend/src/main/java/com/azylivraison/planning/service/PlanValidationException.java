package com.azylivraison.planning.service;

import java.util.List;

import com.azylivraison.planning.api.PlanningValidationError;

public class PlanValidationException extends RuntimeException {

    private final List<PlanningValidationError> errors;

    public PlanValidationException(List<PlanningValidationError> errors) {
        super("Le plan contient des erreurs métier.");
        this.errors = List.copyOf(errors);
    }

    public List<PlanningValidationError> getErrors() {
        return errors;
    }
}

