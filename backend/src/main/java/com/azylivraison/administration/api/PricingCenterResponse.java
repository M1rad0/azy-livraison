package com.azylivraison.administration.api;

import com.azylivraison.pricing.domain.PricingCenter;

public record PricingCenterResponse(
        String label,
        String destinationQuery,
        double latitude,
        double longitude) {

    static PricingCenterResponse from(PricingCenter center) {
        return new PricingCenterResponse(
                center.label(), center.query(), center.latitude(), center.longitude());
    }
}
