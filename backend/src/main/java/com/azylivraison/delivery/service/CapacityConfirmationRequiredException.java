package com.azylivraison.delivery.service;

public class CapacityConfirmationRequiredException extends RuntimeException {

    private final int limit;
    private final int current;
    private final int requested;
    private final int projected;

    public CapacityConfirmationRequiredException(int limit, int current, int requested, int projected) {
        super("La capacité quotidienne sera dépassée. Une confirmation du client est requise.");
        this.limit = limit;
        this.current = current;
        this.requested = requested;
        this.projected = projected;
    }

    public int limit() {
        return limit;
    }

    public int current() {
        return current;
    }

    public int requested() {
        return requested;
    }

    public int projected() {
        return projected;
    }
}
