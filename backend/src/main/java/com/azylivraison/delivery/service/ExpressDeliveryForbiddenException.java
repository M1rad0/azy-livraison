package com.azylivraison.delivery.service;

public class ExpressDeliveryForbiddenException extends RuntimeException {
    public ExpressDeliveryForbiddenException() {
        super("Votre compte n'est pas autorise a creer des livraisons expresses.");
    }
}
