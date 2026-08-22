package com.azylivraison.optimization.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryClient;
import com.azylivraison.planning.domain.DeliveryGroup;
import com.azylivraison.planning.domain.DeliveryPackage;

import org.springframework.stereotype.Component;

@Component
class HubCollectionTaskFactory {

    List<HubCollectionTask> create(List<DeliveryClient> clients, List<Courier> couriers) {
        BigDecimal maximumCapacity = couriers.stream()
                .map(Courier::maxWeightKg)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        List<HubCollectionTask> tasks = new ArrayList<>();

        for (DeliveryClient client : clients) {
            for (DeliveryGroup group : client.deliveryGroups()) {
                BigDecimal totalWeight = group.packages().stream()
                        .map(DeliveryPackage::weightKg)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (totalWeight.compareTo(maximumCapacity) <= 0) {
                    tasks.add(task(
                            "withdrawal-" + group.id(),
                            client,
                            group,
                            group.packages(),
                            totalWeight,
                            HubCollectionTask.Kind.WHOLE_WITHDRAWAL));
                    continue;
                }

                for (DeliveryPackage deliveryPackage : group.packages()) {
                    tasks.add(task(
                            "withdrawal-" + group.id() + "-package-" + deliveryPackage.id(),
                            client,
                            group,
                            List.of(deliveryPackage),
                            deliveryPackage.weightKg(),
                            HubCollectionTask.Kind.SPLIT_PACKAGE));
                }
            }
        }
        return List.copyOf(tasks);
    }

    private HubCollectionTask task(
            String id,
            DeliveryClient client,
            DeliveryGroup group,
            List<DeliveryPackage> packages,
            BigDecimal weight,
            HubCollectionTask.Kind kind) {
        return new HubCollectionTask(
                id,
                client.id(),
                client.name(),
                group.id(),
                group.name(),
                group.pickup(),
                packages,
                weight,
                kind);
    }
}
