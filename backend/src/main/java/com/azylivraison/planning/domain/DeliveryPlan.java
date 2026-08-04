package com.azylivraison.planning.domain;

import java.time.OffsetDateTime;
import java.util.List;

public record DeliveryPlan(
        String schemaVersion,
        PlanningPattern pattern,
        String planId,
        String name,
        OffsetDateTime departureAt,
        String timezone,
        PlanSettings settings,
        Location depot,
        List<Courier> couriers,
        List<DeliveryPackage> packages,
        List<DeliveryClient> clients) {

    public DeliveryPlan {
        couriers = List.copyOf(couriers);
        packages = packages == null ? List.of() : List.copyOf(packages);
        clients = clients == null ? List.of() : List.copyOf(clients);
    }

    public DeliveryPlan(
            String schemaVersion,
            PlanningPattern pattern,
            String planId,
            String name,
            OffsetDateTime departureAt,
            String timezone,
            PlanSettings settings,
            Location depot,
            List<Courier> couriers,
            List<DeliveryPackage> packages) {
        this(schemaVersion, pattern, planId, name, departureAt, timezone, settings, depot, couriers, packages, List.of());
    }

    public List<DeliveryPackage> allPackages() {
        if (pattern == PlanningPattern.SINGLE_PICKUP_MULTI_DELIVERY) {
            return packages;
        }
        return clients.stream()
                .flatMap(client -> client.deliveryGroups().stream())
                .flatMap(group -> group.packages().stream())
                .toList();
    }
}
