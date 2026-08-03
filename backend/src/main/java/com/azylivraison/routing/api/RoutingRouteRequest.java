package com.azylivraison.routing.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RoutingRouteRequest(
        @NotNull(message = "La liste ordonnée des points est obligatoire.")
        @Size(min = 2, message = "Au moins deux points sont nécessaires.")
        List<@Valid RoutingPointRequest> points) {
}
