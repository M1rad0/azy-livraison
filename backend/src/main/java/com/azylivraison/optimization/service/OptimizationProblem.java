package com.azylivraison.optimization.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;

record OptimizationProblem(
        OffsetDateTime planDepartureAt,
        boolean returnToDepot,
        int depotNode,
        List<Courier> couriers,
        List<DeliveryPackage> packages,
        int[] courierStartNodes,
        int[] packageNodes,
        List<List<Double>> distancesMeters) {

    OptimizationProblem {
        couriers = List.copyOf(couriers);
        packages = List.copyOf(packages);
        courierStartNodes = courierStartNodes.clone();
        packageNodes = packageNodes.clone();
        distancesMeters = distancesMeters.stream().map(List::copyOf).toList();
    }
}
