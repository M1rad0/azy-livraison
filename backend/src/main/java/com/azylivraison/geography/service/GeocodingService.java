package com.azylivraison.geography.service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.azylivraison.geography.config.GeocodingProperties;
import com.azylivraison.geography.domain.GeocodingCandidate;
import com.azylivraison.geography.provider.GeocodingProvider;
import com.azylivraison.geography.repository.GeocodingCacheRepository;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

@Service
public class GeocodingService {

    private static final int MIN_QUERY_LENGTH = 3;
    private static final int MAX_QUERY_LENGTH = 300;

    private final GeocodingProvider provider;
    private final GeocodingCacheRepository cacheRepository;
    private final GeocodingProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GeocodingService(
            GeocodingProvider provider,
            GeocodingCacheRepository cacheRepository,
            GeocodingProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.provider = provider;
        this.cacheRepository = cacheRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public GeocodingSearchResult search(String rawQuery) {
        String query = validateAndClean(rawQuery);
        String normalizedQuery = normalize(query);
        String countryCode = properties.countryCode().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();

        return cacheRepository.find(normalizedQuery, countryCode, now)
                .map(cached -> new GeocodingSearchResult(
                        query,
                        cached.provider(),
                        true,
                        cached.createdAt(),
                        deserialize(cached.responseJson())
                        ))
                .orElseGet(() -> searchProvider(query, normalizedQuery, countryCode, now));
    }

    private GeocodingSearchResult searchProvider(
            String query,
            String normalizedQuery,
            String countryCode,
            Instant now) {
        List<GeocodingCandidate> candidates = provider.search(query);
        cacheRepository.save(
                normalizedQuery,
                countryCode,
                provider.name(),
                serialize(candidates),
                now,
                now.plus(properties.cacheTtl()));
        return new GeocodingSearchResult(query, provider.name(), false, null, candidates);
    }

    private String validateAndClean(String rawQuery) {
        if (rawQuery == null) {
            throw new InvalidGeocodingQueryException("Saisissez une adresse à rechercher.");
        }
        String query = rawQuery.strip().replaceAll("\\s+", " ");
        if (query.length() < MIN_QUERY_LENGTH) {
            throw new InvalidGeocodingQueryException(
                    "La recherche doit contenir au moins " + MIN_QUERY_LENGTH + " caractères.");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new InvalidGeocodingQueryException(
                    "La recherche ne peut pas dépasser " + MAX_QUERY_LENGTH + " caractères.");
        }
        return query;
    }

    private String normalize(String query) {
        return Normalizer.normalize(query, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private String serialize(List<GeocodingCandidate> candidates) {
        try {
            return objectMapper.writeValueAsString(candidates);
        } catch (Exception exception) {
            throw new IllegalStateException("Le cache de géocodage n'a pas pu être sérialisé.", exception);
        }
    }

    private List<GeocodingCandidate> deserialize(String responseJson) {
        try {
            GeocodingCandidate[] candidates = objectMapper.readValue(responseJson, GeocodingCandidate[].class);
            return List.copyOf(Arrays.asList(candidates));
        } catch (Exception exception) {
            throw new IllegalStateException("Le cache de géocodage est illisible.", exception);
        }
    }
}
