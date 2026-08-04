package com.azylivraison.optimization.api;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.azylivraison.optimization.service.OptimizationInputException;
import com.azylivraison.planning.api.PlanValidationResponse;
import com.azylivraison.planning.api.PlanningValidationError;
import com.azylivraison.planning.service.PlanValidationException;
import com.azylivraison.routing.service.InvalidRoutingRequestException;
import com.azylivraison.routing.service.RoutingNotFoundException;
import com.azylivraison.routing.service.RoutingUnavailableException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OptimizationController.class)
public class OptimizationExceptionHandler {

    @ExceptionHandler(PlanValidationException.class)
    public ResponseEntity<PlanValidationResponse> handlePlanValidation(PlanValidationException exception) {
        return ResponseEntity.badRequest().body(PlanValidationResponse.failure(exception.getErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<PlanValidationResponse> handleBeanValidation(MethodArgumentNotValidException exception) {
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
    public ResponseEntity<PlanValidationResponse> handleUnreadableJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(PlanValidationResponse.failure(List.of(
                new PlanningValidationError(
                        "request",
                        "MALFORMED_JSON",
                        "Le JSON est invalide ou contient une valeur inconnue."))));
    }

    @ExceptionHandler(OptimizationInputException.class)
    public ResponseEntity<OptimizationErrorResponse> handleInput(OptimizationInputException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new OptimizationErrorResponse(
                "OPTIMIZATION_INPUT_INVALID",
                exception.getMessage(),
                OffsetDateTime.now()));
    }

    @ExceptionHandler(InvalidRoutingRequestException.class)
    public ResponseEntity<OptimizationErrorResponse> handleRoutingInput(InvalidRoutingRequestException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "OPTIMIZATION_SIZE_LIMIT", exception.getMessage());
    }

    @ExceptionHandler(RoutingNotFoundException.class)
    public ResponseEntity<OptimizationErrorResponse> handleRoutingNotFound(RoutingNotFoundException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTING_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(RoutingUnavailableException.class)
    public ResponseEntity<OptimizationErrorResponse> handleRoutingUnavailable(RoutingUnavailableException exception) {
        return error(HttpStatus.BAD_GATEWAY, "ROUTING_UNAVAILABLE", exception.getMessage());
    }

    private ResponseEntity<OptimizationErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new OptimizationErrorResponse(code, message, OffsetDateTime.now()));
    }

    public record OptimizationErrorResponse(
            String code,
            String message,
            OffsetDateTime timestamp) {
    }
}
