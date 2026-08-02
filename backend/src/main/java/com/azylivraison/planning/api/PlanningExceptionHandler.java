package com.azylivraison.planning.api;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.azylivraison.planning.service.PlanValidationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PlanningExceptionHandler {

    @ExceptionHandler(PlanValidationException.class)
    public ResponseEntity<PlanValidationResponse> handlePlanValidation(PlanValidationException exception) {
        return ResponseEntity.badRequest()
                .body(PlanValidationResponse.failure(exception.getErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<PlanValidationResponse> handleBeanValidation(
            MethodArgumentNotValidException exception) {
        List<PlanningValidationError> errors = exception.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    String path = error instanceof FieldError fieldError
                            ? fieldError.getField()
                            : error.getObjectName();
                    String code = error.getCode() == null
                            ? "INVALID_VALUE"
                            : error.getCode().toUpperCase(Locale.ROOT);
                    String message = error.getDefaultMessage() == null
                            ? "La valeur n'est pas valide."
                            : error.getDefaultMessage();
                    return new PlanningValidationError(path, code, message);
                })
                .sorted(Comparator.comparing(PlanningValidationError::path))
                .toList();

        return ResponseEntity.badRequest().body(PlanValidationResponse.failure(errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<PlanValidationResponse> handleUnreadableJson(
            HttpMessageNotReadableException exception) {
        PlanningValidationError error = new PlanningValidationError(
                "request",
                "MALFORMED_JSON",
                "Le JSON est invalide ou contient une valeur inconnue.");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PlanValidationResponse.failure(List.of(error)));
    }
}

