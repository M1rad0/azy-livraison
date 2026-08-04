package com.azylivraison.optimization.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.RoutingPreset;
import com.azylivraison.planning.domain.VehicleType;

public record OptimizationResult(
        String schemaVersion,
        String sourcePlanId,
        OffsetDateTime generatedAt,
        OptimizationStatus status,
        String solverStatus,
        long computationTimeMillis,
        DataContext dataContext,
        OptimizationSummary summary,
        List<CourierRoute> routes,
        List<UnassignedPackage> unassignedPackages,
        List<String> warnings) {

    public OptimizationResult {
        routes = List.copyOf(routes);
        unassignedPackages = List.copyOf(unassignedPackages);
        warnings = List.copyOf(warnings);
    }

    public enum OptimizationStatus {
        SUCCESS,
        SUCCESS_WITH_WARNINGS,
        NO_SOLUTION
    }

    public enum UnassignedReason {
        DESTINATION_UNRESOLVED,
        NO_ROAD_ROUTE,
        OVERWEIGHT_FOR_ALL_COURIERS,
        INSUFFICIENT_TOTAL_CAPACITY,
        TIME_WINDOW_UNSATISFIABLE,
        SOLVER_TIMEOUT,
        NO_FEASIBLE_ASSIGNMENT
    }

    public enum RouteStopType {
        PICKUP,
        DELIVERY
    }

    public record DataContext(
            String routingProvider,
            RoutingPreset routingPreset,
            String optimizer,
            String optimizerVersion) {
    }

    public record OptimizationSummary(
            int packageCount,
            int assignedPackageCount,
            int unassignedPackageCount,
            int courierCount,
            int usedCourierCount,
            BigDecimal totalAssignedWeightKg,
            double totalDistanceMeters,
            double totalDurationSeconds) {
    }

    public record CourierRoute(
            String courierId,
            String courierName,
            VehicleType vehicleType,
            BigDecimal loadedWeightKg,
            BigDecimal maxWeightKg,
            BigDecimal remainingCapacityKg,
            BigDecimal capacityUtilizationPercent,
            Location departureLocation,
            OffsetDateTime departureAt,
            OffsetDateTime completedAt,
            double distanceMeters,
            double durationSeconds,
            List<RouteStop> stops) {

        public CourierRoute {
            stops = List.copyOf(stops);
        }
    }

    public record RouteStop(
            int order,
            RouteStopType type,
            String clientId,
            String clientName,
            String deliveryGroupId,
            String deliveryGroupName,
            String packageId,
            String packageLabel,
            String phone,
            BigDecimal weightKg,
            Location destination,
            OffsetDateTime estimatedArrivalAt,
            double distanceFromPreviousMeters,
            double durationFromPreviousSeconds,
            double cumulativeDistanceMeters,
            double cumulativeDurationSeconds) {

        public RouteStop(
                int order,
                String packageId,
                String packageLabel,
                String phone,
                BigDecimal weightKg,
                Location destination,
                OffsetDateTime estimatedArrivalAt,
                double distanceFromPreviousMeters,
                double durationFromPreviousSeconds,
                double cumulativeDistanceMeters,
                double cumulativeDurationSeconds) {
            this(order, RouteStopType.DELIVERY, null, null, null, null, packageId, packageLabel, phone,
                    weightKg, destination, estimatedArrivalAt, distanceFromPreviousMeters,
                    durationFromPreviousSeconds, cumulativeDistanceMeters, cumulativeDurationSeconds);
        }
    }

    public record UnassignedPackage(
            String packageId,
            String packageLabel,
            BigDecimal weightKg,
            UnassignedReason reason,
            String message,
            BigDecimal minimumRequiredCapacityKg,
            String clientId,
            String clientName,
            String deliveryGroupId,
            String deliveryGroupName) {

        public UnassignedPackage(
                String packageId,
                String packageLabel,
                BigDecimal weightKg,
                UnassignedReason reason,
                String message,
                BigDecimal minimumRequiredCapacityKg) {
            this(packageId, packageLabel, weightKg, reason, message, minimumRequiredCapacityKg,
                    null, null, null, null);
        }
    }
}
