package com.azylivraison.account.service;

public class UsernameAlreadyExistsException extends RuntimeException {

    public UsernameAlreadyExistsException() {
        super("Ce nom d'utilisateur est déjà utilisé.");
    }
}
