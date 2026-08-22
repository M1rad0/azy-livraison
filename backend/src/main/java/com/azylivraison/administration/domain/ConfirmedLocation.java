package com.azylivraison.administration.domain;

public record ConfirmedLocation(
        String label,
        String destinationQuery,
        double latitude,
        double longitude) {
}
