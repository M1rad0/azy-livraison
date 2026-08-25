package com.azylivraison.account.service;

public class ClientProfileNotFoundException extends RuntimeException {

    public ClientProfileNotFoundException() {
        super("Le profil client est introuvable.");
    }
}
