package com.azylivraison.planning.domain;

public record Location(
        String label,
        String destinationQuery,
        Double latitude,
        Double longitude) {
}

