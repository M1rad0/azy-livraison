package com.azylivraison.optimization.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.azylivraison.planning.domain.Courier;

record HubDistributionProblem(
        OffsetDateTime distributionDepartureAt,
        List<Courier> couriers,
        List<MultiClientOptimizationJob> jobs,
        int hubNode,
        int[] deliveryNodes,
        List<List<Double>> distancesMeters) {

    HubDistributionProblem {
        couriers = List.copyOf(couriers);
        jobs = List.copyOf(jobs);
        deliveryNodes = deliveryNodes.clone();
        distancesMeters = distancesMeters.stream().map(List::copyOf).toList();
        if (deliveryNodes.length != jobs.size()) {
            throw new IllegalArgumentException("Un nœud de livraison est requis pour chaque colis.");
        }
    }
}
