package com.azylivraison.geography.provider;

import java.util.List;

import com.azylivraison.geography.domain.GeocodingCandidate;

public interface GeocodingProvider {

    String name();

    List<GeocodingCandidate> search(String query);
}
