package com.azylivraison.routing.api;

import com.azylivraison.routing.service.InvalidRoutingRequestException;
import com.azylivraison.routing.service.RoutingNotFoundException;
import com.azylivraison.routing.service.RoutingUnavailableException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = RoutingController.class)
public class RoutingExceptionHandler {

    @ExceptionHandler({InvalidRoutingRequestException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<RoutingErrorResponse> handleInvalidRequest(Exception exception) {
        String message = exception.getMessage();
        if (exception instanceof MethodArgumentNotValidException validationException
                && validationException.getBindingResult().getFieldError() != null) {
            message = validationException.getBindingResult().getFieldError().getDefaultMessage();
        }
        return ResponseEntity.badRequest()
                .body(new RoutingErrorResponse("INVALID_ROUTING_REQUEST", message));
    }

    @ExceptionHandler(RoutingNotFoundException.class)
    public ResponseEntity<RoutingErrorResponse> handleNotFound(RoutingNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new RoutingErrorResponse("ROUTE_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(RoutingUnavailableException.class)
    public ResponseEntity<RoutingErrorResponse> handleUnavailable(RoutingUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new RoutingErrorResponse("ROUTING_UNAVAILABLE", exception.getMessage()));
    }
}
