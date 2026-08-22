package com.azylivraison.planning.domain;

import java.util.List;

public record DeliveryGroup(
        String id,
        String name,
        Location pickup,
        List<DeliveryPackage> packages) {

    public DeliveryGroup {
        packages = List.copyOf(packages);
    }
}
