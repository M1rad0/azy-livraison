package com.azylivraison.account.service;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Identifiant ou mot de passe incorrect.");
    }
}
