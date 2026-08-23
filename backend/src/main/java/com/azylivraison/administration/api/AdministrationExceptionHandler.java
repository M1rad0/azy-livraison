package com.azylivraison.administration.api;

import java.util.Comparator;
import java.util.List;

import com.azylivraison.administration.service.AdministrationConflictException;
import com.azylivraison.administration.service.AdministrationNotFoundException;
import com.azylivraison.pricing.service.PricingConfigurationException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.azylivraison.administration.api")
public class AdministrationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AdministrationErrorResponse> handleValidation(
            MethodArgumentNotValidException exception) {
        List<AdministrationFieldError> errors = exception.getBindingResult().getAllErrors().stream()
                .map(error -> new AdministrationFieldError(
                        error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                        error.getDefaultMessage() == null ? "Cette valeur n'est pas valide." : error.getDefaultMessage()))
                .sorted(Comparator.comparing(AdministrationFieldError::field))
                .toList();
        return ResponseEntity.badRequest().body(new AdministrationErrorResponse(
                "VALIDATION_FAILED",
                "Validation impossible.",
                errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<AdministrationErrorResponse> handleMalformedRequest(Exception exception) {
        return ResponseEntity.badRequest().body(AdministrationErrorResponse.of(
                "INVALID_REQUEST",
                "La requête contient une valeur invalide ou inconnue."));
    }

    @ExceptionHandler(AdministrationNotFoundException.class)
    public ResponseEntity<AdministrationErrorResponse> handleNotFound(
            AdministrationNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(AdministrationErrorResponse.of(
                exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(AdministrationConflictException.class)
    public ResponseEntity<AdministrationErrorResponse> handleConflict(
            AdministrationConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(AdministrationErrorResponse.of(
                exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(PricingConfigurationException.class)
    public ResponseEntity<AdministrationErrorResponse> handlePricingConfiguration(
            PricingConfigurationException exception) {
        return ResponseEntity.badRequest().body(AdministrationErrorResponse.of(
                "PRICING_SETTINGS_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<AdministrationErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(AdministrationErrorResponse.of(
                "ADMIN_DATA_CONFLICT",
                "Ces informations ne peuvent pas être enregistrées dans leur état actuel."));
    }
}
