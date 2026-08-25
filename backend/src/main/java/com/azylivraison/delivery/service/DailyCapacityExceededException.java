package com.azylivraison.delivery.service;

import java.time.LocalDate;

public class DailyCapacityExceededException extends RuntimeException {

    private final LocalDate requestedDate;
    private final int limit;
    private final int current;
    private final int requested;
    private final int projected;
    private final int remaining;
    private final int excess;

    public DailyCapacityExceededException(
            LocalDate requestedDate,
            int limit,
            int current,
            int requested,
            int projected) {
        super("Cette demande dépasse la capacité du " + requestedDate + " de "
                + Math.max(0, projected - limit) + " colis. Retirez "
                + Math.max(0, requested - Math.max(0, limit - current))
                + " colis pour continuer.");
        this.requestedDate = requestedDate;
        this.limit = limit;
        this.current = current;
        this.requested = requested;
        this.projected = projected;
        this.remaining = Math.max(0, limit - current);
        this.excess = Math.max(0, requested - remaining);
    }

    public LocalDate requestedDate() { return requestedDate; }
    public int limit() { return limit; }
    public int current() { return current; }
    public int requested() { return requested; }
    public int projected() { return projected; }
    public int remaining() { return remaining; }
    public int excess() { return excess; }
}
