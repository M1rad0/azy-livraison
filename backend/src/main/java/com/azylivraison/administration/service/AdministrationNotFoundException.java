package com.azylivraison.administration.service;

public class AdministrationNotFoundException extends RuntimeException {

    private final String code;

    public AdministrationNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
