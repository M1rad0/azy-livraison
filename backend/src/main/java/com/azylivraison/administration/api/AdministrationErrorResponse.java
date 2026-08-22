package com.azylivraison.administration.api;

import java.util.List;

public record AdministrationErrorResponse(
        String code,
        String message,
        List<AdministrationFieldError> errors) {

    static AdministrationErrorResponse of(String code, String message) {
        return new AdministrationErrorResponse(code, message, List.of());
    }
}
