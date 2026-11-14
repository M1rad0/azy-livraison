package com.azylivraison.dispatch.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.VehicleType;

/** Immutable business inputs plus the administrator's editable assignments. */
public record ManualDispatchDraft(
        UUID id, LocalDate serviceDate, long version, String status, Step step,
        Location hub, List<DraftCourier> couriers, List<DispatchSourcePackage> packages,
        List<Pickup> pickups, List<Collection> collections, List<Delivery> deliveries,
        UUID finalPlanId) {

    public ManualDispatchDraft {
        couriers = List.copyOf(couriers);
        packages = List.copyOf(packages);
        pickups = List.copyOf(pickups);
        collections = List.copyOf(collections);
        deliveries = List.copyOf(deliveries);
    }

    public enum Step { COLLECTION, DELIVERY, REVIEW }

    public record DraftCourier(
            UUID courierId, String name, String contact, VehicleType vehicleType,
            BigDecimal maxWeightKg, BigDecimal averageSpeedKmh,
            OffsetDateTime departureAt, Location departure) { }

    public record Pickup(String id, Location location, List<UUID> packageIds) {
        public Pickup { packageIds = List.copyOf(packageIds); }
    }

    public record Collection(String pickupId, UUID courierId, int order) { }
    public record Delivery(UUID packageId, UUID courierId, int order) { }

    public ManualDispatchDraft withLayout(
            long nextVersion, String nextStatus, Step nextStep, List<DraftCourier> nextCouriers,
            List<Collection> nextCollections, List<Delivery> nextDeliveries, UUID planId) {
        return new ManualDispatchDraft(id, serviceDate, nextVersion, nextStatus, nextStep,
                hub, nextCouriers, packages, pickups, nextCollections, nextDeliveries, planId);
    }
}
