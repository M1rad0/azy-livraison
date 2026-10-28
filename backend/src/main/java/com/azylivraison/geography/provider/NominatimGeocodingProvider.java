package com.azylivraison.geography.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.azylivraison.geography.config.GeocodingProperties;
import com.azylivraison.geography.domain.GeocodingBoundingBox;
import com.azylivraison.geography.domain.GeocodingCandidate;
import com.azylivraison.geography.service.GeocodingUnavailableException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class NominatimGeocodingProvider implements GeocodingProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GeocodingProperties properties;

    public NominatimGeocodingProvider(
            @Qualifier("nominatimRestClient") RestClient nominatimRestClient,
            ObjectMapper objectMapper,
            GeocodingProperties properties) {
        this.restClient = nominatimRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "NOMINATIM";
    }

    @Override
    public List<GeocodingCandidate> search(String query, GeocodingSearchOptions options) {
        try {
            String payload = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder
                                .path("/search")
                                .queryParam("q", query)
                                .queryParam("format", "jsonv2")
                                .queryParam("countrycodes", properties.countryCode())
                                .queryParam("accept-language", properties.acceptLanguage())
                                .queryParam("limit", options.bounded()
                                        ? Math.min(40, properties.resultLimit() * 4)
                                        : properties.resultLimit());
                        if (options.bounded()) {
                            SearchViewBox viewBox = viewBox(options);
                            builder.queryParam("viewbox", String.format(
                                            Locale.ROOT,
                                            "%.7f,%.7f,%.7f,%.7f",
                                            viewBox.west(), viewBox.north(), viewBox.east(), viewBox.south()))
                                    .queryParam("bounded", 1);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .body(String.class);
            return parse(payload == null ? "[]" : payload);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new GeocodingUnavailableException(
                    "Le service de géocodage est momentanément indisponible.", exception);
        }
    }

    private SearchViewBox viewBox(GeocodingSearchOptions options) {
        double latitude = options.centerLatitude();
        double longitude = options.centerLongitude();
        double latitudeDelta = options.maximumDistanceMeters() / 111_320.0;
        double longitudeScale = Math.max(0.01, Math.cos(Math.toRadians(latitude)));
        double longitudeDelta = options.maximumDistanceMeters() / (111_320.0 * longitudeScale);
        return new SearchViewBox(
                Math.max(-180, longitude - longitudeDelta),
                Math.min(90, latitude + latitudeDelta),
                Math.min(180, longitude + longitudeDelta),
                Math.max(-90, latitude - latitudeDelta));
    }

    private record SearchViewBox(double west, double north, double east, double south) {
    }

    private List<GeocodingCandidate> parse(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (!root.isArray()) {
                throw new IllegalArgumentException("Nominatim response must be an array");
            }

            List<GeocodingCandidate> candidates = new ArrayList<>();
            for (JsonNode place : root) {
                GeocodingCandidate candidate = candidate(place);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
            return List.copyOf(candidates);
        } catch (Exception exception) {
            throw new GeocodingUnavailableException(
                    "La réponse du service de géocodage est invalide.", exception);
        }
    }

    private GeocodingCandidate candidate(JsonNode place) {
        String osmType = text(place, "osm_type");
        String displayName = text(place, "display_name");
        long osmId = place.path("osm_id").asLong(-1);
        Double latitude = decimal(place, "lat");
        Double longitude = decimal(place, "lon");
        if (osmType == null || displayName == null || osmId < 0 || latitude == null || longitude == null) {
            return null;
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            return null;
        }

        String normalizedOsmType = osmType.toUpperCase(Locale.ROOT);
        String shortOsmType = switch (normalizedOsmType) {
            case "NODE" -> "N";
            case "WAY" -> "W";
            case "RELATION" -> "R";
            default -> normalizedOsmType;
        };
        return new GeocodingCandidate(
                shortOsmType + osmId,
                normalizedOsmType,
                osmId,
                displayName,
                latitude,
                longitude,
                text(place, "category"),
                text(place, "type"),
                decimal(place, "importance"),
                boundingBox(place.path("boundingbox")));
    }

    private GeocodingBoundingBox boundingBox(JsonNode node) {
        if (!node.isArray() || node.size() != 4) {
            return null;
        }
        try {
            return new GeocodingBoundingBox(
                    Double.parseDouble(node.get(0).asString()),
                    Double.parseDouble(node.get(1).asString()),
                    Double.parseDouble(node.get(2).asString()),
                    Double.parseDouble(node.get(3).asString()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asString("").trim();
        return value.isEmpty() ? null : value;
    }

    private Double decimal(JsonNode node, String field) {
        String value = node.path(field).asString("").trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            double number = Double.parseDouble(value);
            return Double.isFinite(number) ? number : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
