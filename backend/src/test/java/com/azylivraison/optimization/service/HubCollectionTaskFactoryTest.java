package com.azylivraison.optimization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryClient;
import com.azylivraison.planning.domain.DeliveryGroup;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.VehicleType;

import org.junit.jupiter.api.Test;

class HubCollectionTaskFactoryTest {

    private final HubCollectionTaskFactory factory = new HubCollectionTaskFactory();

    @Test
    void keepsAWithdrawalWholeWhenOneCourierCanCarryItsTotalWeight() {
        DeliveryGroup group = group("retrait-1", packages("6", "6"));

        List<HubCollectionTask> tasks = factory.create(
                List.of(client(group)),
                List.of(courier("livreur-1", "30")));

        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.id()).isEqualTo("withdrawal-retrait-1");
            assertThat(task.kind()).isEqualTo(HubCollectionTask.Kind.WHOLE_WITHDRAWAL);
            assertThat(task.packages()).hasSize(2);
            assertThat(task.weightKg()).isEqualByComparingTo("12");
        });
    }

    @Test
    void splitsAnOverweightWithdrawalAtPackageBoundaries() {
        DeliveryGroup group = group("retrait-lourd", packages("18", "18"));

        List<HubCollectionTask> tasks = factory.create(
                List.of(client(group)),
                List.of(courier("livreur-1", "30"), courier("livreur-2", "20")));

        assertThat(tasks).hasSize(2);
        assertThat(tasks).allSatisfy(task -> {
            assertThat(task.kind()).isEqualTo(HubCollectionTask.Kind.SPLIT_PACKAGE);
            assertThat(task.packages()).hasSize(1);
            assertThat(task.weightKg()).isEqualByComparingTo("18");
        });
        assertThat(tasks).extracting(HubCollectionTask::id)
                .containsExactly(
                        "withdrawal-retrait-lourd-package-colis-1",
                        "withdrawal-retrait-lourd-package-colis-2");
    }

    @Test
    void excludesPackagesWhoseDestinationHasNoCoordinatesFromCollection() {
        DeliveryPackage resolved = new DeliveryPackage(
                "colis-resolu", "Colis résolu", new BigDecimal("6"), null,
                location("Destination résolue"), null);
        DeliveryPackage unresolved = new DeliveryPackage(
                "colis-non-resolu", "Colis non résolu", new BigDecimal("20"), null,
                new Location("Destination saisie", "Destination saisie", null, null), null);

        List<HubCollectionTask> tasks = factory.create(
                List.of(client(group("retrait-mixte", List.of(resolved, unresolved)))),
                List.of(courier("livreur-1", "30")));

        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.packages()).extracting(DeliveryPackage::id).containsExactly("colis-resolu");
            assertThat(task.weightKg()).isEqualByComparingTo("6");
        });
    }

    private static DeliveryClient client(DeliveryGroup... groups) {
        return new DeliveryClient("client-1", "Client", List.of(groups));
    }

    private static DeliveryGroup group(String id, List<DeliveryPackage> packages) {
        return new DeliveryGroup(id, "Retrait", location("Retrait"), packages);
    }

    private static List<DeliveryPackage> packages(String... weights) {
        java.util.ArrayList<DeliveryPackage> packages = new java.util.ArrayList<>();
        for (int index = 0; index < weights.length; index++) {
            packages.add(new DeliveryPackage(
                    "colis-" + (index + 1),
                    "Colis " + (index + 1),
                    new BigDecimal(weights[index]),
                    null,
                    location("Destination " + (index + 1)),
                    null));
        }
        return List.copyOf(packages);
    }

    private static Courier courier(String id, String capacity) {
        return new Courier(
                id,
                id,
                VehicleType.MOTO,
                new BigDecimal(capacity),
                new BigDecimal("36"),
                OffsetDateTime.of(2026, 8, 22, 6, 0, 0, 0, ZoneOffset.ofHours(3)),
                location(id));
    }

    private static Location location(String label) {
        return new Location(label, label, -18.9, 47.5);
    }
}
