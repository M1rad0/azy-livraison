package com.azylivraison.pricing.service;

public class DestinationOutsidePricingZonesException extends RuntimeException {

    private final long distanceFromCenterMeters;
    private final long maximumCoveredDistanceMeters;

    public DestinationOutsidePricingZonesException(
            long distanceFromCenterMeters,
            long maximumCoveredDistanceMeters) {
        super("Cette destination est hors de la zone de livraison actuellement couverte.");
        this.distanceFromCenterMeters = distanceFromCenterMeters;
        this.maximumCoveredDistanceMeters = maximumCoveredDistanceMeters;
    }

    public long distanceFromCenterMeters() {
        return distanceFromCenterMeters;
    }

    public long maximumCoveredDistanceMeters() {
        return maximumCoveredDistanceMeters;
    }
}
