package com.azylivraison.administration.service;

public class AdministrationConflictException extends RuntimeException {

    private final String code;

    public AdministrationConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
