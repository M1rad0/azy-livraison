package com.azylivraison.optimization.service;

import java.math.BigDecimal;
import java.util.List;

import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.Location;

record HubCollectionTask(
        String id,
        String clientId,
        String clientName,
        String deliveryGroupId,
        String deliveryGroupName,
        Location pickup,
        List<DeliveryPackage> packages,
        BigDecimal weightKg,
        Kind kind) {

    HubCollectionTask {
        packages = List.copyOf(packages);
        if (packages.isEmpty()) {
            throw new IllegalArgumentException("Une tâche de collecte doit contenir au moins un colis.");
        }
        BigDecimal packageWeight = packages.stream()
                .map(DeliveryPackage::weightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weightKg.compareTo(packageWeight) != 0) {
            throw new IllegalArgumentException("Le poids de la tâche doit correspondre à celui de ses colis.");
        }
    }

    int packageCount() {
        return packages.size();
    }

    enum Kind {
        WHOLE_WITHDRAWAL,
        SPLIT_PACKAGE
    }
}
