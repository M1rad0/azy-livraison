package com.azylivraison.delivery.service;

import java.util.UUID;

public class DeliveryRequestNotFoundException extends RuntimeException {

    public DeliveryRequestNotFoundException(UUID id) {
        super("Demande de livraison introuvable : " + id);
    }
}
