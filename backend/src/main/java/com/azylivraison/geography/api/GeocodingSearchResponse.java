package com.azylivraison.geography.api;

import java.time.Instant;
import java.util.List;

import com.azylivraison.geography.domain.GeocodingCandidate;
import com.azylivraison.geography.service.GeocodingSearchResult;

public record GeocodingSearchResponse(
        String query,
        String provider,
        boolean cached,
        Instant cachedAt,
        int resultCount,
        List<GeocodingCandidate> candidates) {

    public static GeocodingSearchResponse from(GeocodingSearchResult result) {
        return new GeocodingSearchResponse(
                result.query(),
                result.provider(),
                result.cached(),
                result.cachedAt(),
                result.candidates().size(),
                result.candidates());
    }
}
