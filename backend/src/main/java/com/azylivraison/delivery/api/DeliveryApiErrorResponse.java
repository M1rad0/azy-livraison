package com.azylivraison.delivery.api;

import java.util.List;

public record DeliveryApiErrorResponse(
        String code,
        String message,
        List<FieldViolation> violations) {

    public record FieldViolation(String field, String message) {
    }
}
