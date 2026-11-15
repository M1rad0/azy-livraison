package com.azylivraison.dailyworkflow.api;

import java.util.Comparator;
import java.util.List;

import com.azylivraison.dailyworkflow.service.DailyWorkflowConflictException;
import com.azylivraison.dailyworkflow.service.DailyWorkflowInputException;
import com.azylivraison.dailyworkflow.service.DailyWorkflowNotFoundException;
import com.azylivraison.delivery.service.DailyCapacityExceededException;
import com.azylivraison.delivery.service.DeliveryDateClosedException;
import com.azylivraison.finance.service.FinanceConflictException;
import com.azylivraison.finance.service.FinanceInputException;
import com.azylivraison.finance.service.FinanceNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        AdminDailyWorkflowController.class,
        ClientPackageWorkflowController.class,
        ClientClosureController.class
})
public class DailyWorkflowExceptionHandler {

    @ExceptionHandler({DailyWorkflowNotFoundException.class, FinanceNotFoundException.class})
    ResponseEntity<DailyWorkflowErrorResponse> notFound(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                DailyWorkflowErrorResponse.of("DAILY_WORKFLOW_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler({DailyWorkflowConflictException.class, FinanceConflictException.class})
    ResponseEntity<DailyWorkflowErrorResponse> conflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                DailyWorkflowErrorResponse.of("DAILY_WORKFLOW_CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler({DailyWorkflowInputException.class, FinanceInputException.class})
    ResponseEntity<DailyWorkflowErrorResponse> invalid(RuntimeException exception) {
        return ResponseEntity.unprocessableEntity().body(
                DailyWorkflowErrorResponse.of("DAILY_WORKFLOW_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(DailyCapacityExceededException.class)
    ResponseEntity<com.azylivraison.delivery.api.DailyCapacityErrorResponse> capacity(
            DailyCapacityExceededException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new com.azylivraison.delivery.api.DailyCapacityErrorResponse(
                        "DAILY_CAPACITY_EXCEEDED", exception.getMessage(), exception.requestedDate(),
                        exception.limit(), exception.current(), exception.requested(), exception.projected(),
                        exception.remaining(), exception.excess()));
    }

    @ExceptionHandler(DeliveryDateClosedException.class)
    ResponseEntity<DailyWorkflowErrorResponse> dateClosed(DeliveryDateClosedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                DailyWorkflowErrorResponse.of("DELIVERY_DATE_CLOSED", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<DailyWorkflowErrorResponse> invalidFields(MethodArgumentNotValidException exception) {
        List<DailyWorkflowErrorResponse.FieldViolation> violations = exception.getBindingResult()
                .getAllErrors().stream()
                .map(error -> new DailyWorkflowErrorResponse.FieldViolation(
                        error instanceof FieldError field ? field.getField() : error.getObjectName(),
                        error.getDefaultMessage() == null ? "Cette valeur n'est pas valide." : error.getDefaultMessage()))
                .sorted(Comparator.comparing(DailyWorkflowErrorResponse.FieldViolation::field))
                .toList();
        return ResponseEntity.badRequest().body(new DailyWorkflowErrorResponse(
                "DAILY_WORKFLOW_VALIDATION_FAILED", "Validation impossible.", violations));
    }
}
