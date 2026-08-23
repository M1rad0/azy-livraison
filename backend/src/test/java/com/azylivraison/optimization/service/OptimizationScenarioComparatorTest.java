package com.azylivraison.optimization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.azylivraison.optimization.domain.OptimizationComparisonResult.OptimizationStrategy;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.RecommendationCriterion;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.StrategyMetrics;

import org.junit.jupiter.api.Test;

class OptimizationScenarioComparatorTest {

    private static final OffsetDateTime END = OffsetDateTime.of(2026, 8, 22, 12, 0, 0, 0, ZoneOffset.ofHours(3));
    private final OptimizationScenarioComparator comparator = new OptimizationScenarioComparator();

    @Test
    void followsTheBusinessPriorityOrder() {
        assertDecision(metrics(9, 9, 0, 20_000, END, 2), metrics(10, 1, 99_999, 99_999, END.plusHours(9), 9),
                OptimizationStrategy.VIA_HUB, RecommendationCriterion.MORE_DELIVERED_PACKAGES);
        assertDecision(metrics(10, 9, 0, 20_000, END, 2), metrics(10, 10, 99_999, 99_999, END.plusHours(9), 9),
                OptimizationStrategy.VIA_HUB, RecommendationCriterion.MORE_ON_TIME_PACKAGES);
        assertDecision(metrics(10, 10, 60, 20_000, END, 2), metrics(10, 10, 59, 99_999, END.plusHours(9), 9),
                OptimizationStrategy.VIA_HUB, RecommendationCriterion.LOWER_TOTAL_LATENESS);
        assertDecision(metrics(10, 10, 0, 20_000, END, 2), metrics(10, 10, 0, 19_999, END.plusHours(9), 2),
                OptimizationStrategy.VIA_HUB, RecommendationCriterion.LOWER_DISTANCE);
        assertDecision(metrics(10, 10, 0, 20_000, END, 2), metrics(10, 10, 0, 20_000, END.minusSeconds(1), 2),
                OptimizationStrategy.VIA_HUB, RecommendationCriterion.EARLIER_COMPLETION);
        assertDecision(metrics(10, 10, 0, 20_000, END, 2), metrics(10, 10, 0, 20_000, END, 3),
                OptimizationStrategy.VIA_HUB, RecommendationCriterion.MORE_COURIERS);
    }

    @Test
    void recommendsDirectOnAnExactTie() {
        StrategyMetrics metrics = metrics(10, 10, 0, 20_000, END, 2);

        assertDecision(metrics, metrics,
                OptimizationStrategy.DIRECT, RecommendationCriterion.EXACT_TIE_DIRECT);
    }

    @Test
    void aOneMeterGainIsEnoughWhenHigherPrioritiesAreEqual() {
        StrategyMetrics direct = metrics(10, 10, 0, 10_000, END, 2);
        StrategyMetrics hub = metrics(10, 10, 0, 9_999, END, 2);

        var decision = comparator.compare(direct, hub);

        assertThat(decision.strategy()).isEqualTo(OptimizationStrategy.VIA_HUB);
        assertThat(decision.delta().totalDistanceMeters()).isEqualTo(-1);
    }

    @Test
    void distributingAcrossMoreCouriersComesBeforeDistance() {
        StrategyMetrics direct = metrics(10, 10, 0, 10_000, END, 2);
        StrategyMetrics hub = metrics(10, 10, 0, 20_000, END, 3);

        var decision = comparator.compare(direct, hub);

        assertThat(decision.strategy()).isEqualTo(OptimizationStrategy.VIA_HUB);
        assertThat(decision.criterion()).isEqualTo(RecommendationCriterion.MORE_COURIERS);
    }

    private void assertDecision(
            StrategyMetrics direct,
            StrategyMetrics hub,
            OptimizationStrategy strategy,
            RecommendationCriterion criterion) {
        var decision = comparator.compare(direct, hub);
        assertThat(decision.strategy()).isEqualTo(strategy);
        assertThat(decision.criterion()).isEqualTo(criterion);
    }

    private StrategyMetrics metrics(
            int assigned,
            int onTime,
            long totalLateness,
            long distance,
            OffsetDateTime completedAt,
            int couriers) {
        return new StrategyMetrics(
                10,
                assigned,
                onTime,
                assigned - onTime,
                totalLateness,
                totalLateness,
                distance,
                completedAt,
                couriers,
                0,
                0,
                0);
    }
}
