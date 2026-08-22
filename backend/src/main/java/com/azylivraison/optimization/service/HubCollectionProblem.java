package com.azylivraison.optimization.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.azylivraison.planning.domain.Courier;

record HubCollectionProblem(
        List<Courier> couriers,
        List<HubCollectionTask> tasks,
        int hubNode,
        int[] courierStartNodes,
        int[] taskNodes,
        int[] matrixPointByNode,
        List<List<Double>> distancesMeters,
        OffsetDateTime latestHubArrivalAt) {

    HubCollectionProblem(
            List<Courier> couriers,
            List<HubCollectionTask> tasks,
            int hubNode,
            int[] courierStartNodes,
            int[] taskNodes,
            int[] matrixPointByNode,
            List<List<Double>> distancesMeters) {
        this(
                couriers,
                tasks,
                hubNode,
                courierStartNodes,
                taskNodes,
                matrixPointByNode,
                distancesMeters,
                null);
    }

    HubCollectionProblem {
        couriers = List.copyOf(couriers);
        tasks = List.copyOf(tasks);
        courierStartNodes = courierStartNodes.clone();
        taskNodes = taskNodes.clone();
        matrixPointByNode = matrixPointByNode.clone();
        distancesMeters = distancesMeters.stream()
                .map(row -> Collections.unmodifiableList(new ArrayList<>(row)))
                .toList();

        if (couriers.isEmpty()) {
            throw new IllegalArgumentException("Au moins un livreur est nécessaire pour la collecte.");
        }
        if (couriers.size() != courierStartNodes.length) {
            throw new IllegalArgumentException("Chaque livreur doit posséder un nœud de départ.");
        }
        if (tasks.size() != taskNodes.length) {
            throw new IllegalArgumentException("Chaque tâche doit posséder un nœud de retrait.");
        }
        if (hubNode < 0 || hubNode >= matrixPointByNode.length) {
            throw new IllegalArgumentException("Le nœud du siège est invalide.");
        }
        validateNodes(courierStartNodes, matrixPointByNode.length, "départ");
        validateNodes(taskNodes, matrixPointByNode.length, "retrait");
        validateDistinctRoutingNodes(hubNode, courierStartNodes, taskNodes);
        validateMatrix(matrixPointByNode, distancesMeters);
        for (Courier courier : couriers) {
            if (courier.departureAt() == null || courier.departureLocation() == null) {
                throw new IllegalArgumentException(
                        "Chaque livreur doit posséder une origine et une heure pour la collecte via le siège.");
            }
        }
    }

    private static void validateDistinctRoutingNodes(int hubNode, int[] courierStarts, int[] taskNodes) {
        Set<Integer> nodes = new HashSet<>();
        nodes.add(hubNode);
        for (int node : courierStarts) {
            if (!nodes.add(node)) {
                throw new IllegalArgumentException("Les nœuds de départ et du siège doivent être distincts.");
            }
        }
        for (int node : taskNodes) {
            if (!nodes.add(node)) {
                throw new IllegalArgumentException("Chaque tâche doit posséder un nœud de routage distinct.");
            }
        }
    }

    private static void validateNodes(int[] nodes, int nodeCount, String label) {
        for (int node : nodes) {
            if (node < 0 || node >= nodeCount) {
                throw new IllegalArgumentException("Un nœud de " + label + " est invalide.");
            }
        }
    }

    private static void validateMatrix(int[] matrixPointByNode, List<List<Double>> distances) {
        int matrixSize = distances.size();
        if (matrixSize == 0 || distances.stream().anyMatch(row -> row.size() != matrixSize)) {
            throw new IllegalArgumentException("La matrice routière doit être carrée.");
        }
        for (int matrixPoint : matrixPointByNode) {
            if (matrixPoint < 0 || matrixPoint >= matrixSize) {
                throw new IllegalArgumentException("Un nœud référence un point absent de la matrice.");
            }
        }
    }
}
