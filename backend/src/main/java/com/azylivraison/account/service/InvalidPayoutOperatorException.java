package com.azylivraison.account.service;

public class InvalidPayoutOperatorException extends RuntimeException {
    public InvalidPayoutOperatorException() {
        super("Choisissez MVola, Orange Money ou Airtel Money pour le numéro de versement.");
    }
}
