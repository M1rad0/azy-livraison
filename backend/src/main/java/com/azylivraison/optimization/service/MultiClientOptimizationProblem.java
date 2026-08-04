package com.azylivraison.optimization.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.azylivraison.planning.domain.Courier;

record MultiClientOptimizationProblem(
        OffsetDateTime planDepartureAt,
        List<Courier> couriers,
        List<MultiClientOptimizationJob> jobs,
        int[] courierStartNodes,
        int[] pickupNodes,
        int[] deliveryNodes,
        int[] matrixPointByNode,
        List<List<Double>> distancesMeters) {

    MultiClientOptimizationProblem {
        couriers = List.copyOf(couriers);
        jobs = List.copyOf(jobs);
        courierStartNodes = courierStartNodes.clone();
        pickupNodes = pickupNodes.clone();
        deliveryNodes = deliveryNodes.clone();
        matrixPointByNode = matrixPointByNode.clone();
        distancesMeters = distancesMeters.stream().map(List::copyOf).toList();
    }
}
