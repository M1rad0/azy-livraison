package com.azylivraison.optimization.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.azylivraison.optimization.domain.OperationalPolicy;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.StrategyMetrics;
import com.azylivraison.optimization.domain.OptimizationResult;
import com.azylivraison.optimization.domain.OptimizationResult.CourierRoute;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStop;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStopType;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.DeliveryTimeWindow;
import com.azylivraison.planning.domain.DeliveryPlan;
import com.azylivraison.planning.domain.PlanningPattern;

/** Recomputes physical comparison metrics without relying on an optimizer score. */
final class OptimizationScenarioEvaluator {

    StrategyMetrics evaluate(
            DeliveryPlan plan,
            OptimizationResult result,
            OperationalPolicy operationalPolicy) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(operationalPolicy, "operationalPolicy");

        List<DeliveryPackage> planPackages = plan.allPackages();
        Map<String, DeliveryPackage> packagesById = packagesById(planPackages);
        Map<String, RouteStop> deliveriesByPackageId = uniqueDeliveries(result.routes());

        LatenessMetrics lateness = lateness(deliveriesByPackageId, packagesById);
        RouteMetrics routes = routeMetrics(plan, result.routes(), operationalPolicy);

        long totalDistanceMeters = roundedNonNegative(result.routes().stream()
                .mapToDouble(CourierRoute::distanceMeters)
                .sum());
        OffsetDateTime completedAt = result.routes().stream()
                .map(CourierRoute::completedAt)
                .filter(Objects::nonNull)
                .max(Comparator.comparing(OffsetDateTime::toInstant))
                .orElse(null);
        int usedCourierCount = (int) result.routes().stream()
                .map(CourierRoute::courierId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        return new StrategyMetrics(
                planPackages.size(),
                deliveriesByPackageId.size(),
                lateness.onTimePackageCount(),
                lateness.latePackageCount(),
                lateness.totalLatenessSeconds(),
                lateness.maxLatenessSeconds(),
                totalDistanceMeters,
                completedAt,
                usedCourierCount,
                routes.drivingSeconds(),
                routes.waitingSeconds(),
                routes.serviceSeconds());
    }

    private Map<String, DeliveryPackage> packagesById(List<DeliveryPackage> packages) {
        Map<String, DeliveryPackage> byId = new HashMap<>();
        for (DeliveryPackage deliveryPackage : packages) {
            if (deliveryPackage.id() != null) {
                byId.putIfAbsent(deliveryPackage.id(), deliveryPackage);
            }
        }
        return Map.copyOf(byId);
    }

    private Map<String, RouteStop> uniqueDeliveries(List<CourierRoute> routes) {
        Map<String, RouteStop> deliveries = new LinkedHashMap<>();
        for (CourierRoute route : routes) {
            for (RouteStop stop : orderedStops(route)) {
                if (stop.type() != RouteStopType.DELIVERY || stop.packageId() == null) continue;
                deliveries.merge(stop.packageId(), stop, this::earlierStop);
            }
        }
        return Map.copyOf(deliveries);
    }

    private RouteStop earlierStop(RouteStop first, RouteStop second) {
        if (first.estimatedArrivalAt() == null) return second;
        if (second.estimatedArrivalAt() == null) return first;
        return first.estimatedArrivalAt().toInstant().isAfter(second.estimatedArrivalAt().toInstant())
                ? second
                : first;
    }

    private LatenessMetrics lateness(
            Map<String, RouteStop> deliveriesByPackageId,
            Map<String, DeliveryPackage> packagesById) {
        int onTime = 0;
        int late = 0;
        long totalLateness = 0;
        long maximumLateness = 0;

        for (Map.Entry<String, RouteStop> delivery : deliveriesByPackageId.entrySet()) {
            DeliveryPackage deliveryPackage = packagesById.get(delivery.getKey());
            DeliveryTimeWindow window = deliveryPackage == null ? null : deliveryPackage.deliveryWindow();
            if (window == null) {
                onTime++;
                continue;
            }

            OffsetDateTime serviceStartedAt = Objects.requireNonNull(
                    delivery.getValue().estimatedArrivalAt(),
                    "A delivery stop with a time window must expose estimatedArrivalAt");
            long latenessSeconds = Math.max(0, Duration.between(
                    window.endAt().toInstant(),
                    serviceStartedAt.toInstant()).toSeconds());
            if (latenessSeconds == 0) {
                onTime++;
            } else {
                late++;
                totalLateness = Math.addExact(totalLateness, latenessSeconds);
                maximumLateness = Math.max(maximumLateness, latenessSeconds);
            }
        }
        return new LatenessMetrics(onTime, late, totalLateness, maximumLateness);
    }

    private RouteMetrics routeMetrics(
            DeliveryPlan plan,
            List<CourierRoute> routes,
            OperationalPolicy operationalPolicy) {
        long totalDriving = 0;
        long totalWaiting = 0;
        long totalService = 0;
        Set<String> servicedDeliveryPackages = new HashSet<>();
        Map<String, Courier> couriersById = new HashMap<>();
        for (Courier courier : plan.couriers()) {
            if (courier.id() != null) couriersById.putIfAbsent(courier.id(), courier);
        }

        for (CourierRoute route : routes) {
            List<RouteStop> stops = orderedStops(route);
            long driving = drivingSeconds(route, stops, couriersById);
            long service = serviceSeconds(
                    stops,
                    servicedDeliveryPackages,
                    operationalPolicy,
                    plan.pattern() == PlanningPattern.SINGLE_PICKUP_MULTI_DELIVERY);
            long elapsed = roundedNonNegative(route.durationSeconds());
            long waiting = Math.max(0, elapsed - driving - service);

            totalDriving = Math.addExact(totalDriving, driving);
            totalWaiting = Math.addExact(totalWaiting, waiting);
            totalService = Math.addExact(totalService, service);
        }
        return new RouteMetrics(totalDriving, totalWaiting, totalService);
    }

    private long drivingSeconds(
            CourierRoute route,
            List<RouteStop> stops,
            Map<String, Courier> couriersById) {
        Courier courier = couriersById.get(route.courierId());
        if (courier != null
                && courier.averageSpeedKmh() != null
                && courier.averageSpeedKmh().signum() > 0) {
            return roundedNonNegative(
                    route.distanceMeters() * 3.6 / courier.averageSpeedKmh().doubleValue());
        }
        return roundedNonNegative(stops.stream()
                .mapToDouble(RouteStop::durationFromPreviousSeconds)
                .sum());
    }

    private long serviceSeconds(
            List<RouteStop> stops,
            Set<String> servicedDeliveryPackages,
            OperationalPolicy operationalPolicy,
            boolean hasImplicitSinglePickup) {
        long service = hasImplicitSinglePickup
                ? operationalPolicy.pickupServiceDuration().toSeconds()
                : 0;
        boolean previousWasPickup = false;
        String previousPickupGroupId = null;

        for (RouteStop stop : stops) {
            if (stop.type() == RouteStopType.PICKUP) {
                boolean startsNewVisit = !previousWasPickup
                        || stop.deliveryGroupId() == null
                        || !stop.deliveryGroupId().equals(previousPickupGroupId);
                if (!hasImplicitSinglePickup && startsNewVisit) {
                    service = Math.addExact(service, operationalPolicy.pickupServiceDuration().toSeconds());
                }
                previousWasPickup = true;
                previousPickupGroupId = stop.deliveryGroupId();
                continue;
            }

            previousWasPickup = false;
            previousPickupGroupId = null;
            if (stop.type() == RouteStopType.DELIVERY
                    && stop.packageId() != null
                    && servicedDeliveryPackages.add(stop.packageId())) {
                service = Math.addExact(service, operationalPolicy.deliveryServiceDuration().toSeconds());
            }
        }
        return service;
    }

    private List<RouteStop> orderedStops(CourierRoute route) {
        List<RouteStop> stops = new ArrayList<>(route.stops());
        stops.sort(Comparator.comparingInt(RouteStop::order));
        return stops;
    }

    private long roundedNonNegative(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("A physical metric must be finite");
        }
        return Math.max(0, Math.round(value));
    }

    private record LatenessMetrics(
            int onTimePackageCount,
            int latePackageCount,
            long totalLatenessSeconds,
            long maxLatenessSeconds) {
    }

    private record RouteMetrics(
            long drivingSeconds,
            long waitingSeconds,
            long serviceSeconds) {
    }
}
