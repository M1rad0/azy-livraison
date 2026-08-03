package com.azylivraison.geography.service;

public class InvalidGeocodingQueryException extends RuntimeException {

    public InvalidGeocodingQueryException(String message) {
        super(message);
    }
}
