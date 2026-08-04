package com.azylivraison.planning.domain;

import java.math.BigDecimal;

public record DeliveryPackage(
        String id,
        String label,
        BigDecimal weightKg,
        String phone,
        Location destination,
        DeliveryTimeWindow deliveryWindow) {
}
