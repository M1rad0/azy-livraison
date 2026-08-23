package com.azylivraison.pricing.domain;

public record PricingCenter(
        String label,
        String query,
        double latitude,
        double longitude) {
}
