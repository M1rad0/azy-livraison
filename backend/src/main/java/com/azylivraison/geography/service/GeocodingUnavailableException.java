package com.azylivraison.geography.service;

public class GeocodingUnavailableException extends RuntimeException {

    public GeocodingUnavailableException(String message) {
        super(message);
    }

    public GeocodingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
