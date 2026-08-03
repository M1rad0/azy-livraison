package com.azylivraison.routing.service;

public class InvalidRoutingRequestException extends RuntimeException {

    public InvalidRoutingRequestException(String message) {
        super(message);
    }
}
