package com.azylivraison.dispatch.api;

import java.util.List;

import com.azylivraison.dispatch.domain.DispatchPlanDetails;
import com.azylivraison.dispatch.domain.DispatchPlanCourierSnapshot;
import com.azylivraison.dispatch.domain.DispatchStop;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.HubExecution;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OperationalPolicySnapshot;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.StrategyMetrics;
import com.azylivraison.optimization.domain.OptimizationResult;

public record DispatchPlanResponse(
        DispatchPlanSummaryResponse plan,
        OperationalPolicySnapshot operationalPolicy,
        OptimizationResult result,
        HubExecution hubExecution,
        StrategyMetrics metrics,
        List<DispatchPackageResponse> packages,
        List<DispatchPlanCourierSnapshot> couriers,
        List<DispatchStop> stops) {

    static DispatchPlanResponse from(DispatchPlanDetails details) {
        return new DispatchPlanResponse(
                DispatchPlanSummaryResponse.from(details.plan(), details.editable()),
                details.operationalPolicy(),
                details.result(),
                details.hubExecution(),
                details.metrics(),
                details.packages().stream().map(DispatchPackageResponse::from).toList(),
                details.couriers(),
                details.stops());
    }
}
