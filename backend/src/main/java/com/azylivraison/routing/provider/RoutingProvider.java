package com.azylivraison.routing.provider;

import java.util.List;

import com.azylivraison.routing.domain.RoutingPoint;

public interface RoutingProvider {

    String name();

    RoutingMatrixData matrix(List<RoutingPoint> points);

    RoutingRouteData route(List<RoutingPoint> points);
}
