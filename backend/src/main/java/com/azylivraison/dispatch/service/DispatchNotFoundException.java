package com.azylivraison.dispatch.service;

public class DispatchNotFoundException extends RuntimeException {
    public DispatchNotFoundException() {
        super("Ce planning n'existe pas.");
    }
}
