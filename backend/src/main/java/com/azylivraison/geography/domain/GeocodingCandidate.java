package com.azylivraison.geography.domain;

public record GeocodingCandidate(
        String id,
        String osmType,
        long osmId,
        String displayName,
        double latitude,
        double longitude,
        String category,
        String type,
        Double importance,
        GeocodingBoundingBox boundingBox) {
}
