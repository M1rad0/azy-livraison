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
        List<DeliveryPackage> packages) {

    public DeliveryPlan {
        couriers = List.copyOf(couriers);
        packages = List.copyOf(packages);
    }
}

