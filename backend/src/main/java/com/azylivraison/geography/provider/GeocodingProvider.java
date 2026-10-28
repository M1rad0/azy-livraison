package com.azylivraison.geography.provider;

import java.util.List;

import com.azylivraison.geography.domain.GeocodingCandidate;

public interface GeocodingProvider {

    String name();

    default List<GeocodingCandidate> search(String query) {
        return search(query, GeocodingSearchOptions.unrestricted());
    }

    List<GeocodingCandidate> search(String query, GeocodingSearchOptions options);
}
