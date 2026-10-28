package com.azylivraison.geography.service;

import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.geography.domain.GeocodingPurpose;

public record GeocodingSearchScope(
        GeocodingPurpose purpose,
        DeliveryRequestKind requestKind,
        UUID clientAccountId) {

    public static GeocodingSearchScope general() {
        return new GeocodingSearchScope(GeocodingPurpose.GENERAL, null, null);
    }

    public static GeocodingSearchScope destination(
            DeliveryRequestKind requestKind,
            UUID clientAccountId) {
        return new GeocodingSearchScope(
                GeocodingPurpose.DESTINATION,
                requestKind == null ? DeliveryRequestKind.NORMAL : requestKind,
                clientAccountId);
    }
}
