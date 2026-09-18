package com.azylivraison.delivery.service;

public class InvalidDeliveryRequestException extends RuntimeException {

    public InvalidDeliveryRequestException(String message) {
        super(message);
    }
}
