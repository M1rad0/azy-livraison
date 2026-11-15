package com.azylivraison.finance.api;

import java.util.Comparator;
import java.util.List;

import com.azylivraison.finance.service.FinanceConflictException;
import com.azylivraison.finance.service.FinanceInputException;
import com.azylivraison.finance.service.FinanceNotFoundException;
import com.azylivraison.finance.service.ProofStorageException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice(basePackages = "com.azylivraison.finance")
public class FinanceExceptionHandler {

    @ExceptionHandler(FinanceNotFoundException.class)
    ResponseEntity<FinanceErrorResponse> notFound(FinanceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(FinanceErrorResponse.of("FINANCE_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(FinanceConflictException.class)
    ResponseEntity<FinanceErrorResponse> conflict(FinanceConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(FinanceErrorResponse.of("FINANCE_CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler(FinanceInputException.class)
    ResponseEntity<FinanceErrorResponse> invalid(FinanceInputException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(FinanceErrorResponse.of("FINANCE_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(ProofStorageException.class)
    ResponseEntity<FinanceErrorResponse> storage(ProofStorageException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(FinanceErrorResponse.of(
                        "FINANCE_PROOF_STORAGE_ERROR",
                        "Le justificatif ne peut pas être utilisé pour le moment."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<FinanceErrorResponse> validation(MethodArgumentNotValidException exception) {
        List<FinanceErrorResponse.FieldViolation> fields = exception.getBindingResult().getAllErrors().stream()
                .map(error -> new FinanceErrorResponse.FieldViolation(
                        error instanceof FieldError field ? field.getField() : error.getObjectName(),
                        error.getDefaultMessage() == null ? "Cette valeur n'est pas valide." : error.getDefaultMessage()))
                .sorted(Comparator.comparing(FinanceErrorResponse.FieldViolation::field))
                .toList();
        return ResponseEntity.badRequest().body(new FinanceErrorResponse(
                "FINANCE_VALIDATION_FAILED", "Validation impossible.", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestPartException.class})
    ResponseEntity<FinanceErrorResponse> malformed(Exception exception) {
        return ResponseEntity.badRequest().body(FinanceErrorResponse.of(
                "FINANCE_INVALID_REQUEST", "La requête de versement est incomplète ou invalide."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<FinanceErrorResponse> tooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(FinanceErrorResponse.of(
                "FINANCE_PROOF_TOO_LARGE", "Le justificatif doit peser au maximum 10 Mo."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<FinanceErrorResponse> integrity(DataIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(FinanceErrorResponse.of(
                "FINANCE_DATA_CONFLICT",
                "Cette opération financière ne peut pas être enregistrée dans son état actuel."));
    }

    public record FinanceErrorResponse(
            String code,
            String message,
            List<FieldViolation> fields) {

        static FinanceErrorResponse of(String code, String message) {
            return new FinanceErrorResponse(code, message, List.of());
        }

        public record FieldViolation(String field, String message) {
        }
    }
}
