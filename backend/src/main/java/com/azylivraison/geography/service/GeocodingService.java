package com.azylivraison.geography.service;

import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import com.azylivraison.geography.config.GeocodingProperties;
import com.azylivraison.geography.domain.GeocodingCandidate;
import com.azylivraison.geography.domain.GeocodingPurpose;
import com.azylivraison.geography.provider.GeocodingProvider;
import com.azylivraison.geography.provider.GeocodingSearchOptions;
import com.azylivraison.geography.repository.GeocodingCacheRepository;
import com.azylivraison.pricing.domain.AdvancedPricingPolicy;
import com.azylivraison.pricing.domain.PricingDistanceMode;
import com.azylivraison.pricing.repository.AdvancedPricingPolicyRepository;
import com.azylivraison.routing.domain.RoutingPoint;
import com.azylivraison.routing.service.RoutingNotFoundException;
import com.azylivraison.routing.service.RoutingService;
import com.azylivraison.routing.service.RoutingUnavailableException;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

@Service
public class GeocodingService {

    private static final int MIN_QUERY_LENGTH = 3;
    private static final int MAX_QUERY_LENGTH = 300;
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private final GeocodingProvider provider;
    private final GeocodingCacheRepository cacheRepository;
    private final GeocodingProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AdvancedPricingPolicyRepository pricingPolicyRepository;
    private final RoutingService routingService;

    public GeocodingService(
            GeocodingProvider provider,
            GeocodingCacheRepository cacheRepository,
            GeocodingProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            AdvancedPricingPolicyRepository pricingPolicyRepository,
            RoutingService routingService) {
        this.provider = provider;
        this.cacheRepository = cacheRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.pricingPolicyRepository = pricingPolicyRepository;
        this.routingService = routingService;
    }

    public GeocodingSearchResult search(String rawQuery) {
        return search(rawQuery, GeocodingSearchScope.general());
    }

    public GeocodingSearchResult search(String rawQuery, GeocodingSearchScope scope) {
        String query = validateAndClean(rawQuery);
        String normalizedQuery = normalize(query);
        String countryCode = properties.countryCode().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        DestinationRestriction restriction = destinationRestriction(scope);
        String cacheKey = restriction == null
                ? normalizedQuery
                : destinationCacheKey(normalizedQuery, restriction);

        return cacheRepository.find(cacheKey, countryCode, now)
                .map(cached -> new GeocodingSearchResult(
                        query,
                        cached.provider(),
                        true,
                        cached.createdAt(),
                        deserialize(cached.responseJson())
                        ))
                .orElseGet(() -> searchProvider(query, cacheKey, countryCode, now, restriction));
    }

    private GeocodingSearchResult searchProvider(
            String query,
            String normalizedQuery,
            String countryCode,
            Instant now,
            DestinationRestriction restriction) {
        GeocodingSearchOptions options = restriction == null
                ? GeocodingSearchOptions.unrestricted()
                : GeocodingSearchOptions.bounded(
                        restriction.policy().center().latitude(),
                        restriction.policy().center().longitude(),
                        restriction.maximumDistanceMeters());
        List<GeocodingCandidate> candidates = provider.search(query, options);
        if (restriction != null) {
            candidates = filterReachableDestinations(candidates, restriction).stream()
                    .limit(properties.resultLimit())
                    .toList();
        }
        cacheRepository.save(
                normalizedQuery,
                countryCode,
                provider.name(),
                serialize(candidates),
                now,
                now.plus(properties.cacheTtl()));
        return new GeocodingSearchResult(query, provider.name(), false, null, candidates);
    }

    private DestinationRestriction destinationRestriction(GeocodingSearchScope scope) {
        if (scope == null || scope.purpose() == null
                || scope.purpose() != GeocodingPurpose.DESTINATION) {
            return null;
        }
        AdvancedPricingPolicy policy = pricingPolicyRepository
                .findEffective(scope.clientAccountId(), scope.requestKind())
                .orElseThrow(() -> new GeocodingUnavailableException(
                        "Aucune zone tarifaire active ne permet de rechercher cette destination."));
        if (policy.zones().isEmpty()) {
            throw new GeocodingUnavailableException(
                    "Aucune zone tarifaire active ne permet de rechercher cette destination.");
        }
        return new DestinationRestriction(policy, policy.zones().getLast().maxDistanceMeters());
    }

    private List<GeocodingCandidate> filterReachableDestinations(
            List<GeocodingCandidate> candidates,
            DestinationRestriction restriction) {
        AdvancedPricingPolicy policy = restriction.policy();
        List<GeocodingCandidate> insideAirRadius = candidates.stream()
                .filter(candidate -> haversineMeters(
                        policy.center().latitude(), policy.center().longitude(),
                        candidate.latitude(), candidate.longitude()) <= restriction.maximumDistanceMeters())
                .toList();
        if (insideAirRadius.isEmpty() || policy.distanceMode() == PricingDistanceMode.AIR_DISTANCE) {
            return insideAirRadius;
        }

        List<RoutingPoint> points = new ArrayList<>(insideAirRadius.size() + 1);
        points.add(new RoutingPoint("pricing-center", policy.center().latitude(), policy.center().longitude()));
        for (int index = 0; index < insideAirRadius.size(); index++) {
            GeocodingCandidate candidate = insideAirRadius.get(index);
            points.add(new RoutingPoint("destination-" + index, candidate.latitude(), candidate.longitude()));
        }
        try {
            List<Double> distances = routingService.matrix(points).distancesMeters().getFirst();
            List<GeocodingCandidate> reachable = new ArrayList<>();
            for (int index = 0; index < insideAirRadius.size(); index++) {
                Double distance = distances.get(index + 1);
                if (distance != null && distance <= restriction.maximumDistanceMeters()) {
                    reachable.add(insideAirRadius.get(index));
                }
            }
            return List.copyOf(reachable);
        } catch (RoutingNotFoundException | RoutingUnavailableException exception) {
            throw new GeocodingUnavailableException(
                    "La couverture routiere des zones tarifaires ne peut pas etre verifiee pour le moment.",
                    exception);
        }
    }

    private String destinationCacheKey(String normalizedQuery, DestinationRestriction restriction) {
        AdvancedPricingPolicy policy = restriction.policy();
        String client = policy.clientAccountId() == null ? "default" : policy.clientAccountId().toString();
        return "destination:" + policy.requestKind().name().toLowerCase(Locale.ROOT)
                + ":" + client
                + ":" + policy.id()
                + ":" + policy.version()
                + ":" + policy.distanceMode().name().toLowerCase(Locale.ROOT)
                + ":" + restriction.maximumDistanceMeters()
                + ":" + sha256(normalizedQuery);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private double haversineMeters(
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude) {
        double fromLatitudeRadians = Math.toRadians(fromLatitude);
        double toLatitudeRadians = Math.toRadians(toLatitude);
        double latitudeDelta = Math.toRadians(toLatitude - fromLatitude);
        double longitudeDelta = Math.toRadians(toLongitude - fromLongitude);
        double sinLatitude = Math.sin(latitudeDelta / 2);
        double sinLongitude = Math.sin(longitudeDelta / 2);
        double haversine = sinLatitude * sinLatitude
                + Math.cos(fromLatitudeRadians) * Math.cos(toLatitudeRadians)
                * sinLongitude * sinLongitude;
        double bounded = Math.min(1, Math.max(0, haversine));
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(bounded), Math.sqrt(1 - bounded));
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

    private record DestinationRestriction(
            AdvancedPricingPolicy policy,
            long maximumDistanceMeters) {
    }
}
