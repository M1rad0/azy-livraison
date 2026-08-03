package com.azylivraison.geography.service;

import java.time.Instant;
import java.util.List;

import com.azylivraison.geography.domain.GeocodingCandidate;

public record GeocodingSearchResult(
        String query,
        String provider,
        boolean cached,
        Instant cachedAt,
        List<GeocodingCandidate> candidates) {
}
