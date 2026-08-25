package com.azylivraison.delivery.domain;

public record ConfirmedLocation(
        String label,
        String query,
        double latitude,
        double longitude) {
}
