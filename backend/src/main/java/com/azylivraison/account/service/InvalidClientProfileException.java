package com.azylivraison.account.service;

public class InvalidClientProfileException extends RuntimeException {

    public InvalidClientProfileException(String message) {
        super(message);
    }
}
