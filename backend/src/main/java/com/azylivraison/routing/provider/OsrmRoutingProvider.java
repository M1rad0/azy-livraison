package com.azylivraison.routing.provider;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.azylivraison.routing.config.RoutingProperties;
import com.azylivraison.routing.domain.GeoJsonLineString;
import com.azylivraison.routing.domain.RoutingLeg;
import com.azylivraison.routing.domain.RoutingPoint;
import com.azylivraison.routing.domain.RoutingWaypoint;
import com.azylivraison.routing.service.RoutingNotFoundException;
import com.azylivraison.routing.service.RoutingUnavailableException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OsrmRoutingProvider implements RoutingProvider {

    private static final String NO_ROUTE_MESSAGE =
            "Aucun trajet routier ne relie tous les points demandés.";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RoutingProperties properties;
    private final String baseUrl;

    public OsrmRoutingProvider(
            @Qualifier("osrmRestClient") RestClient osrmRestClient,
            ObjectMapper objectMapper,
            RoutingProperties properties) {
        this.restClient = osrmRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.baseUrl = properties.baseUrl().toString().replaceAll("/+$", "");
    }

    @Override
    public String name() {
        return "OSRM";
    }

    @Override
    public RoutingMatrixData matrix(List<RoutingPoint> points) {
        String path = "/table/v1/" + properties.providerProfile() + "/" + coordinates(points)
                + "?annotations=duration,distance";
        return execute(path, payload -> parseMatrix(payload, points));
    }

    @Override
    public RoutingRouteData route(List<RoutingPoint> points) {
        String path = "/route/v1/" + properties.providerProfile() + "/" + coordinates(points)
                + "?alternatives=false&steps=false&annotations=false&overview=full&geometries=geojson";
        return execute(path, payload -> parseRoute(payload, points));
    }

    private <T> T execute(String path, PayloadParser<T> parser) {
        try {
            String payload = restClient.get()
                    .uri(URI.create(baseUrl + path))
                    .retrieve()
                    .body(String.class);
            return parser.parse(payload == null ? "{}" : payload);
        } catch (RoutingNotFoundException | RoutingUnavailableException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (isNoRoutePayload(exception.getResponseBodyAsString())) {
                throw new RoutingNotFoundException(NO_ROUTE_MESSAGE);
            }
            throw unavailable(exception);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable(exception);
        }
    }

    private RoutingMatrixData parseMatrix(String payload, List<RoutingPoint> points) {
        JsonNode root = readRoot(payload);
        ensureOk(root);
        int size = points.size();
        List<List<Double>> durations = matrix(root.path("durations"), size, "durations");
        List<List<Double>> distances = matrix(root.path("distances"), size, "distances");
        JsonNode waypointNodes = root.path("sources").isArray()
                ? root.path("sources")
                : root.path("destinations");
        return new RoutingMatrixData(
                waypoints(waypointNodes, points),
                durations,
                distances);
    }

    private RoutingRouteData parseRoute(String payload, List<RoutingPoint> points) {
        JsonNode root = readRoot(payload);
        ensureOk(root);
        JsonNode routes = root.path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            throw invalidPayload("routes");
        }

        JsonNode route = routes.get(0);
        double distance = nonNegativeNumber(route.path("distance"), "distance");
        double duration = nonNegativeNumber(route.path("duration"), "duration");
        GeoJsonLineString geometry = geometry(route.path("geometry"));
        List<RoutingWaypoint> waypoints = waypoints(root.path("waypoints"), points);
        List<RoutingLeg> legs = legs(route.path("legs"), points);
        return new RoutingRouteData(distance, duration, geometry, waypoints, legs);
    }

    private JsonNode readRoot(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (!root.isObject()) {
                throw invalidPayload("root");
            }
            return root;
        } catch (RoutingUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RoutingUnavailableException(
                    "La réponse du service de routage est invalide.", exception);
        }
    }

    private void ensureOk(JsonNode root) {
        String code = root.path("code").asString("");
        if ("Ok".equals(code)) {
            return;
        }
        if (code.startsWith("No")) {
            throw new RoutingNotFoundException(NO_ROUTE_MESSAGE);
        }
        throw new RoutingUnavailableException(
                "Le service de routage a refusé la requête" + messageSuffix(root) + ".");
    }

    private List<List<Double>> matrix(JsonNode node, int expectedSize, String field) {
        if (!node.isArray() || node.size() != expectedSize) {
            throw invalidPayload(field);
        }
        List<List<Double>> rows = new ArrayList<>();
        for (JsonNode rowNode : node) {
            if (!rowNode.isArray() || rowNode.size() != expectedSize) {
                throw invalidPayload(field);
            }
            List<Double> row = new ArrayList<>();
            for (JsonNode value : rowNode) {
                if (value.isNull()) {
                    row.add(null);
                } else {
                    row.add(nonNegativeNumber(value, field));
                }
            }
            rows.add(Collections.unmodifiableList(row));
        }
        return List.copyOf(rows);
    }

    private List<RoutingWaypoint> waypoints(JsonNode nodes, List<RoutingPoint> points) {
        if (!nodes.isArray() || nodes.size() != points.size()) {
            throw invalidPayload("waypoints");
        }
        List<RoutingWaypoint> waypoints = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            JsonNode location = node.path("location");
            if (!location.isArray() || location.size() != 2) {
                throw invalidPayload("waypoint.location");
            }
            RoutingPoint input = points.get(index);
            waypoints.add(new RoutingWaypoint(
                    input.id(),
                    input.latitude(),
                    input.longitude(),
                    number(location.get(1), "waypoint.latitude"),
                    number(location.get(0), "waypoint.longitude"),
                    nonNegativeNumber(node.path("distance"), "waypoint.distance"),
                    nullableText(node, "name")));
        }
        return List.copyOf(waypoints);
    }

    private List<RoutingLeg> legs(JsonNode nodes, List<RoutingPoint> points) {
        if (!nodes.isArray() || nodes.size() != points.size() - 1) {
            throw invalidPayload("legs");
        }
        List<RoutingLeg> legs = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            legs.add(new RoutingLeg(
                    index,
                    index + 1,
                    points.get(index).id(),
                    points.get(index + 1).id(),
                    nonNegativeNumber(node.path("distance"), "leg.distance"),
                    nonNegativeNumber(node.path("duration"), "leg.duration"),
                    nullableText(node, "summary")));
        }
        return List.copyOf(legs);
    }

    private GeoJsonLineString geometry(JsonNode node) {
        if (!node.isObject()
                || !"LineString".equals(node.path("type").asString(""))
                || !node.path("coordinates").isArray()
                || node.path("coordinates").size() < 2) {
            throw invalidPayload("geometry");
        }
        List<List<Double>> coordinates = new ArrayList<>();
        for (JsonNode coordinate : node.path("coordinates")) {
            if (!coordinate.isArray() || coordinate.size() != 2) {
                throw invalidPayload("geometry.coordinates");
            }
            coordinates.add(List.of(
                    number(coordinate.get(0), "geometry.longitude"),
                    number(coordinate.get(1), "geometry.latitude")));
        }
        return new GeoJsonLineString(List.copyOf(coordinates));
    }

    private double nonNegativeNumber(JsonNode node, String field) {
        double value = number(node, field);
        if (value < 0) {
            throw invalidPayload(field);
        }
        return value;
    }

    private double number(JsonNode node, String field) {
        if (!node.isNumber()) {
            throw invalidPayload(field);
        }
        double value = node.asDouble();
        if (!Double.isFinite(value)) {
            throw invalidPayload(field);
        }
        return value;
    }

    private String nullableText(JsonNode node, String field) {
        String value = node.path(field).asString("").strip();
        return value.isEmpty() ? null : value;
    }

    private String coordinates(List<RoutingPoint> points) {
        return points.stream()
                .map(point -> String.format(
                        Locale.ROOT,
                        "%.7f,%.7f",
                        point.longitude(),
                        point.latitude()))
                .reduce((left, right) -> left + ";" + right)
                .orElseThrow();
    }

    private boolean isNoRoutePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return false;
        }
        try {
            String code = objectMapper.readTree(payload).path("code").asString("");
            return "NoRoute".equals(code)
                    || "NoTable".equals(code)
                    || "NoSegment".equals(code);
        } catch (Exception exception) {
            return false;
        }
    }

    private String messageSuffix(JsonNode root) {
        String message = root.path("message").asString("").strip();
        return message.isEmpty() ? "" : " : " + message;
    }

    private RoutingUnavailableException invalidPayload(String field) {
        return new RoutingUnavailableException(
                "La réponse du service de routage est invalide (" + field + ").");
    }

    private RoutingUnavailableException unavailable(Exception cause) {
        return new RoutingUnavailableException(
                "Le service de routage est momentanément indisponible.", cause);
    }

    @FunctionalInterface
    private interface PayloadParser<T> {
        T parse(String payload);
    }
}
