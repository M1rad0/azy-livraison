package com.azylivraison.geography.api;

import com.azylivraison.geography.service.GeocodingUnavailableException;
import com.azylivraison.geography.service.InvalidGeocodingQueryException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = GeocodingController.class)
public class GeocodingExceptionHandler {

    @ExceptionHandler(InvalidGeocodingQueryException.class)
    public ResponseEntity<GeocodingErrorResponse> handleInvalidQuery(InvalidGeocodingQueryException exception) {
        return ResponseEntity.badRequest().body(new GeocodingErrorResponse("INVALID_GEOCODING_QUERY", exception.getMessage()));
    }

    @ExceptionHandler(GeocodingUnavailableException.class)
    public ResponseEntity<GeocodingErrorResponse> handleUnavailable(GeocodingUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new GeocodingErrorResponse("GEOCODING_UNAVAILABLE", exception.getMessage()));
    }
}
