package com.azylivraison.optimization.domain;

import com.azylivraison.optimization.domain.OptimizationComparisonResult.HubExecution;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OperationalPolicySnapshot;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.StrategyMetrics;

/** Public, persistence-friendly result of the sole front-office strategy. */
public record ViaHubOptimizationResult(
        OptimizationResult result,
        HubExecution hubExecution,
        StrategyMetrics metrics,
        OperationalPolicySnapshot operationalPolicy) {
}
