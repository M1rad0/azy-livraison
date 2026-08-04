package com.azylivraison.routing.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.azylivraison.routing.config.RoutingProperties;
import com.azylivraison.routing.domain.RoutingPoint;
import com.azylivraison.routing.domain.UnreachablePair;
import com.azylivraison.routing.provider.RoutingMatrixData;
import com.azylivraison.routing.provider.RoutingProvider;
import com.azylivraison.routing.provider.RoutingRouteData;

import org.springframework.stereotype.Service;

@Service
public class RoutingService {

    private final RoutingProvider provider;
    private final RoutingProperties properties;

    public RoutingService(RoutingProvider provider, RoutingProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    public RoutingMatrixResult matrix(List<RoutingPoint> points) {
        validate(points, properties.maxMatrixPoints());
        RoutingMatrixData data = provider.matrix(points);
        return new RoutingMatrixResult(
                provider.name(),
                properties.preset(),
                data.waypoints(),
                data.durationsSeconds(),
                data.distancesMeters(),
                unreachablePairs(points, data.durationsSeconds()));
    }

    public RoutingRouteResult route(List<RoutingPoint> points) {
        validate(points, properties.maxRoutePoints());
        RoutingRouteData data = provider.route(points);
        return new RoutingRouteResult(
                provider.name(),
                properties.preset(),
                data.distanceMeters(),
                data.durationSeconds(),
                data.geometry(),
                data.waypoints(),
                data.legs());
    }

    private void validate(List<RoutingPoint> points, int maximum) {
        if (points == null || points.size() < 2) {
            throw new InvalidRoutingRequestException("Au moins deux points sont nécessaires.");
        }
        if (points.size() > maximum) {
            throw new InvalidRoutingRequestException(
                    "La requête ne peut pas dépasser " + maximum + " points.");
        }

        Set<String> identifiers = new HashSet<>();
        for (RoutingPoint point : points) {
            if (!identifiers.add(point.id())) {
                throw new InvalidRoutingRequestException(
                        "Chaque point doit avoir un identifiant unique : " + point.id() + ".");
            }
        }
    }

    private List<UnreachablePair> unreachablePairs(
            List<RoutingPoint> points,
            List<List<Double>> durations) {
        List<UnreachablePair> pairs = new ArrayList<>();
        for (int from = 0; from < durations.size(); from++) {
            for (int to = 0; to < durations.get(from).size(); to++) {
                if (durations.get(from).get(to) == null) {
                    pairs.add(new UnreachablePair(
                            from,
                            to,
                            points.get(from).id(),
                            points.get(to).id()));
                }
            }
        }
        return List.copyOf(pairs);
    }
}
