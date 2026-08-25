package com.azylivraison.delivery.service;

import java.time.LocalDate;

public class DeliveryDateClosedException extends RuntimeException {

    private final LocalDate requestedDate;

    public DeliveryDateClosedException(LocalDate requestedDate) {
        super("Le plan du " + requestedDate
                + " est déjà en préparation ou enregistré. Cette date n'accepte plus de colis.");
        this.requestedDate = requestedDate;
    }

    public LocalDate requestedDate() {
        return requestedDate;
    }
}
