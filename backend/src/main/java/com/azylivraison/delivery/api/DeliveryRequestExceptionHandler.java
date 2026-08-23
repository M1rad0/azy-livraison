package com.azylivraison.delivery.api;

import java.util.Comparator;
import java.util.List;

import com.azylivraison.delivery.service.CapacityConfirmationRequiredException;
import com.azylivraison.delivery.service.DeliveryRequestNotFoundException;
import com.azylivraison.delivery.service.InvalidDeliveryRequestFilterException;
import com.azylivraison.pricing.service.DestinationOutsidePricingZonesException;
import com.azylivraison.pricing.service.PricingConfigurationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        ClientDeliveryRequestController.class,
        ClientDeliveryPricingController.class,
        AdminDeliveryRequestController.class
})
public class DeliveryRequestExceptionHandler {

    @ExceptionHandler(CapacityConfirmationRequiredException.class)
    ResponseEntity<CapacityConfirmationErrorResponse> capacityConfirmationRequired(
            CapacityConfirmationRequiredException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new CapacityConfirmationErrorResponse(
                "DAILY_CAPACITY_CONFIRMATION_REQUIRED",
                exception.getMessage(),
                exception.limit(),
                exception.current(),
                exception.requested(),
                exception.projected()));
    }

    @ExceptionHandler(DestinationOutsidePricingZonesException.class)
    ResponseEntity<DeliveryApiErrorResponse> destinationOutsideCoverage(
            DestinationOutsidePricingZonesException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new DeliveryApiErrorResponse(
                "DESTINATION_OUTSIDE_PRICING_ZONES",
                exception.getMessage(),
                List.of()));
    }

    @ExceptionHandler(PricingConfigurationException.class)
    ResponseEntity<DeliveryApiErrorResponse> invalidPricingRequest(PricingConfigurationException exception) {
        return ResponseEntity.badRequest().body(new DeliveryApiErrorResponse(
                "DELIVERY_PRICING_INVALID",
                exception.getMessage(),
                List.of()));
    }

    @ExceptionHandler(DeliveryRequestNotFoundException.class)
    ResponseEntity<DeliveryApiErrorResponse> notFound(DeliveryRequestNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new DeliveryApiErrorResponse(
                "DELIVERY_REQUEST_NOT_FOUND",
                "Cette demande de livraison est introuvable.",
                List.of()));
    }

    @ExceptionHandler(InvalidDeliveryRequestFilterException.class)
    ResponseEntity<DeliveryApiErrorResponse> invalidFilter(InvalidDeliveryRequestFilterException exception) {
        return ResponseEntity.badRequest().body(new DeliveryApiErrorResponse(
                "DELIVERY_REQUEST_FILTER_INVALID",
                exception.getMessage(),
                List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<DeliveryApiErrorResponse> invalidRequest(MethodArgumentNotValidException exception) {
        List<DeliveryApiErrorResponse.FieldViolation> violations = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new DeliveryApiErrorResponse.FieldViolation(
                        error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(DeliveryApiErrorResponse.FieldViolation::field))
                .toList();
        return ResponseEntity.badRequest().body(new DeliveryApiErrorResponse(
                "DELIVERY_REQUEST_INVALID",
                "La demande de livraison contient des informations invalides.",
                violations));
    }
}
