package com.azylivraison.dispatch.api;

import java.util.Comparator;
import java.util.List;

import com.azylivraison.dispatch.service.DispatchConflictException;
import com.azylivraison.dispatch.service.DispatchExecutionException;
import com.azylivraison.dispatch.service.DispatchInputException;
import com.azylivraison.dispatch.service.DispatchNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {AdminDispatchController.class, ManualDispatchController.class})
public class DispatchExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<DispatchErrorResponse> invalidFields(MethodArgumentNotValidException exception) {
        List<DispatchErrorResponse.FieldViolation> errors = exception.getBindingResult().getAllErrors().stream()
                .map(error -> new DispatchErrorResponse.FieldViolation(
                        error instanceof FieldError field ? field.getField() : error.getObjectName(),
                        error.getDefaultMessage() == null ? "Cette valeur n'est pas valide." : error.getDefaultMessage()))
                .sorted(Comparator.comparing(DispatchErrorResponse.FieldViolation::field))
                .toList();
        return ResponseEntity.badRequest().body(new DispatchErrorResponse(
                "DISPATCH_VALIDATION_FAILED",
                "Validation impossible.",
                null,
                errors));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class
    })
    ResponseEntity<DispatchErrorResponse> malformedRequest(Exception exception) {
        return ResponseEntity.badRequest().body(DispatchErrorResponse.of(
                "DISPATCH_INVALID_REQUEST",
                "La demande de dispatch contient une valeur invalide ou incomplète."));
    }

    @ExceptionHandler(DispatchInputException.class)
    ResponseEntity<DispatchErrorResponse> invalidInput(DispatchInputException exception) {
        return ResponseEntity.unprocessableEntity().body(DispatchErrorResponse.of(
                "DISPATCH_INPUT_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(DispatchConflictException.class)
    ResponseEntity<DispatchErrorResponse> conflict(DispatchConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(DispatchErrorResponse.of(
                "DISPATCH_CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler(DispatchNotFoundException.class)
    ResponseEntity<DispatchErrorResponse> notFound(DispatchNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(DispatchErrorResponse.of(
                "DISPATCH_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(DispatchExecutionException.class)
    ResponseEntity<DispatchErrorResponse> executionFailure(DispatchExecutionException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new DispatchErrorResponse(
                "DISPATCH_EXECUTION_FAILED",
                exception.getMessage(),
                exception.dispatchPlanId(),
                List.of()));
    }
}
