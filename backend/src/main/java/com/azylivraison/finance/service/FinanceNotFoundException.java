package com.azylivraison.finance.service;

public class FinanceNotFoundException extends RuntimeException {

    public FinanceNotFoundException(String message) {
        super(message);
    }
}
