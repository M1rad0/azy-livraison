package com.azylivraison.optimization.service;

import com.azylivraison.optimization.domain.OptimizationComparisonResult.HubExecution;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.StrategyMetrics;
import com.azylivraison.optimization.domain.OptimizationResult;

record HubOptimizationOutcome(
        OptimizationResult result,
        HubExecution hubExecution,
        StrategyMetrics metrics) {
}
