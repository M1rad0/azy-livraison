package com.azylivraison.routing.api;

import java.util.List;

import com.azylivraison.routing.domain.RoutingPoint;
import com.azylivraison.routing.service.RoutingService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/routing")
public class RoutingController {

    private final RoutingService routingService;

    public RoutingController(RoutingService routingService) {
        this.routingService = routingService;
    }

    @PostMapping("/matrix")
    public RoutingMatrixResponse matrix(@Valid @RequestBody RoutingMatrixRequest request) {
        return RoutingMatrixResponse.from(routingService.matrix(toDomain(request.points())));
    }

    @PostMapping("/route")
    public RoutingRouteResponse route(@Valid @RequestBody RoutingRouteRequest request) {
        return RoutingRouteResponse.from(routingService.route(toDomain(request.points())));
    }

    private List<RoutingPoint> toDomain(List<RoutingPointRequest> points) {
        return points.stream().map(RoutingPointRequest::toDomain).toList();
    }
}
