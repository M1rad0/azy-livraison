package com.azylivraison.account.domain;

public record DefaultPickupLocation(
        String label,
        String query,
        Double latitude,
        Double longitude) {
}
