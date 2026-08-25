package com.azylivraison.account.service;

public class ClientProfileVersionConflictException extends RuntimeException {

    public ClientProfileVersionConflictException() {
        super("Ces coordonnées ont été modifiées entre-temps. Rechargez le profil avant de réessayer.");
    }
}
