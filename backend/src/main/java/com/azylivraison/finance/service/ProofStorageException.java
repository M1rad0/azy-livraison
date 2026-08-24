package com.azylivraison.finance.service;

public class ProofStorageException extends RuntimeException {

    public ProofStorageException(String message) {
        super(message);
    }

    public ProofStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
