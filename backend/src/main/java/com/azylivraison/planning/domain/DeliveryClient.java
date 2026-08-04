package com.azylivraison.planning.domain;

import java.util.List;

public record DeliveryClient(
        String id,
        String name,
        List<DeliveryGroup> deliveryGroups) {

    public DeliveryClient {
        deliveryGroups = List.copyOf(deliveryGroups);
    }
}
