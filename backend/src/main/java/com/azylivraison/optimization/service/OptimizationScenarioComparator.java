package com.azylivraison.optimization.service;

import java.time.Duration;
import java.util.Objects;

import com.azylivraison.optimization.domain.OptimizationComparisonResult.ComparisonDelta;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OptimizationStrategy;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.RecommendationCriterion;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.StrategyMetrics;

import org.springframework.stereotype.Component;

/** Applies the business priority order to already evaluated physical metrics. */
@Component
class OptimizationScenarioComparator {

    ComparisonDecision compare(StrategyMetrics direct, StrategyMetrics viaHub) {
        Objects.requireNonNull(direct, "direct");
        Objects.requireNonNull(viaHub, "viaHub");

        if (direct.assignedPackageCount() != viaHub.assignedPackageCount()) {
            return decision(
                    largerIsHub(direct.assignedPackageCount(), viaHub.assignedPackageCount()),
                    RecommendationCriterion.MORE_DELIVERED_PACKAGES,
                    "Cette option permet de livrer davantage de colis.",
                    direct,
                    viaHub);
        }
        if (direct.onTimePackageCount() != viaHub.onTimePackageCount()) {
            return decision(
                    largerIsHub(direct.onTimePackageCount(), viaHub.onTimePackageCount()),
                    RecommendationCriterion.MORE_ON_TIME_PACKAGES,
                    "Cette option livre davantage de colis à l’heure.",
                    direct,
                    viaHub);
        }
        if (direct.totalLatenessSeconds() != viaHub.totalLatenessSeconds()) {
            return decision(
                    smallerIsHub(direct.totalLatenessSeconds(), viaHub.totalLatenessSeconds()),
                    RecommendationCriterion.LOWER_TOTAL_LATENESS,
                    "Cette option réduit le retard cumulé.",
                    direct,
                    viaHub);
        }
        if (direct.usedCourierCount() != viaHub.usedCourierCount()) {
            return decision(
                    largerIsHub(direct.usedCourierCount(), viaHub.usedCourierCount()),
                    RecommendationCriterion.MORE_COURIERS,
                    "Cette option répartit la charge entre davantage de livreurs.",
                    direct,
                    viaHub);
        }
        if (direct.totalDistanceMeters() != viaHub.totalDistanceMeters()) {
            return decision(
                    smallerIsHub(direct.totalDistanceMeters(), viaHub.totalDistanceMeters()),
                    RecommendationCriterion.LOWER_DISTANCE,
                    "Cette option réduit la distance totale parcourue.",
                    direct,
                    viaHub);
        }

        int completionComparison = compareCompletion(direct, viaHub);
        if (completionComparison != 0) {
            return decision(
                    completionComparison > 0,
                    RecommendationCriterion.EARLIER_COMPLETION,
                    "Cette option termine les livraisons plus tôt.",
                    direct,
                    viaHub);
        }
        return new ComparisonDecision(
                OptimizationStrategy.DIRECT,
                RecommendationCriterion.EXACT_TIE_DIRECT,
                "Les deux options sont équivalentes ; le trajet direct est conservé par simplicité.",
                delta(direct, viaHub));
    }

    private ComparisonDecision decision(
            boolean hubWins,
            RecommendationCriterion criterion,
            String reason,
            StrategyMetrics direct,
            StrategyMetrics viaHub) {
        return new ComparisonDecision(
                hubWins ? OptimizationStrategy.VIA_HUB : OptimizationStrategy.DIRECT,
                criterion,
                reason,
                delta(direct, viaHub));
    }

    private boolean largerIsHub(long direct, long viaHub) {
        return viaHub > direct;
    }

    private boolean smallerIsHub(long direct, long viaHub) {
        return viaHub < direct;
    }

    private int compareCompletion(StrategyMetrics direct, StrategyMetrics viaHub) {
        if (direct.completedAt() == null && viaHub.completedAt() == null) return 0;
        if (direct.completedAt() == null) return 1;
        if (viaHub.completedAt() == null) return -1;
        return direct.completedAt().toInstant().compareTo(viaHub.completedAt().toInstant());
    }

    private ComparisonDelta delta(StrategyMetrics direct, StrategyMetrics viaHub) {
        Long completionSeconds = direct.completedAt() == null || viaHub.completedAt() == null
                ? null
                : Duration.between(direct.completedAt().toInstant(), viaHub.completedAt().toInstant()).toSeconds();
        return new ComparisonDelta(
                viaHub.assignedPackageCount() - direct.assignedPackageCount(),
                viaHub.onTimePackageCount() - direct.onTimePackageCount(),
                viaHub.totalLatenessSeconds() - direct.totalLatenessSeconds(),
                viaHub.totalDistanceMeters() - direct.totalDistanceMeters(),
                completionSeconds,
                viaHub.usedCourierCount() - direct.usedCourierCount());
    }

    record ComparisonDecision(
            OptimizationStrategy strategy,
            RecommendationCriterion criterion,
            String reason,
            ComparisonDelta delta) {
    }
}
