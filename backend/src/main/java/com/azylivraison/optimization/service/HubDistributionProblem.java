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
        List<List<Double>> distancesMeters,
        long[] vehicleActivationCostMeters) {

    HubDistributionProblem(
            OffsetDateTime distributionDepartureAt,
            List<Courier> couriers,
            List<MultiClientOptimizationJob> jobs,
            int hubNode,
            int[] deliveryNodes,
            List<List<Double>> distancesMeters) {
        this(
                distributionDepartureAt,
                couriers,
                jobs,
                hubNode,
                deliveryNodes,
                distancesMeters,
                new long[couriers.size()]);
    }

    HubDistributionProblem {
        couriers = List.copyOf(couriers);
        jobs = List.copyOf(jobs);
        deliveryNodes = deliveryNodes.clone();
        distancesMeters = distancesMeters.stream().map(List::copyOf).toList();
        vehicleActivationCostMeters = vehicleActivationCostMeters.clone();
        if (deliveryNodes.length != jobs.size()) {
            throw new IllegalArgumentException("Un nœud de livraison est requis pour chaque colis.");
        }
        if (vehicleActivationCostMeters.length != couriers.size()) {
            throw new IllegalArgumentException("Un coût d'approche est requis pour chaque livreur.");
        }
        for (long activationCost : vehicleActivationCostMeters) {
            if (activationCost < 0) {
                throw new IllegalArgumentException("Le coût d'approche d'un livreur ne peut pas être négatif.");
            }
        }
    }
}
