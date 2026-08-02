package com.azylivraison.account.service;

public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("La session a expiré. Reconnectez-vous.");
    }
}
