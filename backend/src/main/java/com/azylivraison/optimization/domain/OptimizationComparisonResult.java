package com.azylivraison.optimization.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.azylivraison.planning.domain.Location;

/**
 * Comparison between the historical direct strategy and a redistribution through
 * a common meeting point. All values exposed here are physical/user-facing
 * metrics; OR-Tools internal objective values must never be used in this contract.
 */
public record OptimizationComparisonResult(
        String schemaVersion,
        String sourcePlanId,
        OffsetDateTime generatedAt,
        OptimizationStrategy recommendedStrategy,
        RecommendationCriterion recommendationCriterion,
        String recommendationReason,
        OperationalPolicySnapshot operationalPolicy,
        OptimizationScenario direct,
        OptimizationScenario viaHub,
        ComparisonDelta delta,
        HubExecution hubExecution) {

    public enum OptimizationStrategy {
        DIRECT,
        VIA_HUB
    }

    public enum RecommendationCriterion {
        MORE_DELIVERED_PACKAGES,
        MORE_ON_TIME_PACKAGES,
        LOWER_TOTAL_LATENESS,
        LOWER_DISTANCE,
        EARLIER_COMPLETION,
        FEWER_COURIERS,
        EXACT_TIE_DIRECT
    }

    public record OperationalPolicySnapshot(
            long pickupServiceSeconds,
            long hubTransferSeconds,
            long deliveryServiceSeconds,
            long deliveryWindowEarlyToleranceSeconds,
            long deliveryWindowLateToleranceSeconds,
            String windowAppliesTo) {
    }

    public record OptimizationScenario(
            OptimizationStrategy strategy,
            OptimizationResult result,
            StrategyMetrics metrics) {
    }

    public record StrategyMetrics(
            int packageCount,
            int assignedPackageCount,
            int onTimePackageCount,
            int latePackageCount,
            long totalLatenessSeconds,
            long maxLatenessSeconds,
            long totalDistanceMeters,
            OffsetDateTime completedAt,
            int usedCourierCount,
            long drivingSeconds,
            long waitingSeconds,
            long serviceSeconds) {

        public boolean fullCoverage() {
            return packageCount > 0 && assignedPackageCount == packageCount;
        }
    }

    /** Signed values are always VIA_HUB minus DIRECT. */
    public record ComparisonDelta(
            int assignedPackageCount,
            int onTimePackageCount,
            long totalLatenessSeconds,
            long totalDistanceMeters,
            Long completionSeconds,
            int usedCourierCount) {
    }

    public record HubExecution(
            Location hub,
            OffsetDateTime meetingAt,
            OffsetDateTime redistributionCompletedAt,
            long collectionDistanceMeters,
            long distributorApproachDistanceMeters,
            long distributionDistanceMeters,
            long hubWaitingSeconds,
            List<CourierHubManifest> manifests,
            List<PackageTransfer> packageTransfers) {

        public HubExecution {
            manifests = List.copyOf(manifests);
            packageTransfers = List.copyOf(packageTransfers);
        }
    }

    public record CourierHubManifest(
            String courierId,
            String courierName,
            List<HubRole> roles,
            OffsetDateTime arrivedAt,
            OffsetDateTime departedAt,
            long waitingSeconds,
            BigDecimal loadAtArrivalKg,
            BigDecimal loadAtDepartureKg,
            List<PackageReference> collectedPackages,
            List<PackageReference> keptPackages,
            List<PackageReference> transferredOutPackages,
            List<PackageReference> receivedPackages,
            List<PackageReference> deliveredPackages) {

        public CourierHubManifest {
            roles = List.copyOf(roles);
            collectedPackages = List.copyOf(collectedPackages);
            keptPackages = List.copyOf(keptPackages);
            transferredOutPackages = List.copyOf(transferredOutPackages);
            receivedPackages = List.copyOf(receivedPackages);
            deliveredPackages = List.copyOf(deliveredPackages);
        }
    }

    public enum HubRole {
        COLLECTION,
        DISTRIBUTION
    }

    public record PackageReference(
            String packageId,
            String packageLabel,
            BigDecimal weightKg,
            String clientId,
            String clientName,
            String deliveryGroupId,
            String deliveryGroupName) {
    }

    public record PackageTransfer(
            PackageReference deliveryPackage,
            String collectorCourierId,
            String collectorCourierName,
            String distributorCourierId,
            String distributorCourierName,
            boolean transferred,
            OffsetDateTime arrivedAtHub,
            OffsetDateTime departedHub) {
    }
}
