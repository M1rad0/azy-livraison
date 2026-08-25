package com.azylivraison.delivery.api;

import com.azylivraison.delivery.domain.ConfirmedLocation;

public record LocationResponse(
        String label,
        String query,
        double latitude,
        double longitude) {

    public static LocationResponse from(ConfirmedLocation location) {
        return new LocationResponse(location.label(), location.query(), location.latitude(), location.longitude());
    }
}
