package com.azylivraison.dispatch.domain;

import java.util.List;

import com.azylivraison.optimization.domain.OptimizationComparisonResult.HubExecution;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OperationalPolicySnapshot;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.StrategyMetrics;
import com.azylivraison.optimization.domain.OptimizationResult;

public record DispatchPlanDetails(
        DispatchPlanRecord plan,
        OperationalPolicySnapshot operationalPolicy,
        OptimizationResult result,
        HubExecution hubExecution,
        StrategyMetrics metrics,
        List<DispatchPackageOutcome> packages) {

    public DispatchPlanDetails {
        packages = List.copyOf(packages);
    }
}
