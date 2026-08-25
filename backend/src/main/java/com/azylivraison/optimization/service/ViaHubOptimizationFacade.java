package com.azylivraison.optimization.service;

import com.azylivraison.optimization.config.OptimizationProperties;
import com.azylivraison.optimization.domain.OperationalPolicy;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OperationalPolicySnapshot;
import com.azylivraison.optimization.domain.ViaHubOptimizationResult;
import com.azylivraison.optimization.domain.ViaHubRunPolicy;
import com.azylivraison.planning.domain.DeliveryPlan;
import com.azylivraison.planning.domain.PlanningPattern;

import org.springframework.stereotype.Service;

/** Runs only collection -> hub -> redistribution -> delivery, never the direct comparison. */
@Service
public class ViaHubOptimizationFacade {

    private final HubOptimizationService hubOptimizationService;
    private final OptimizationProperties properties;

    public ViaHubOptimizationFacade(
            HubOptimizationService hubOptimizationService,
            OptimizationProperties properties) {
        this.hubOptimizationService = hubOptimizationService;
        this.properties = properties;
    }

    public ViaHubOptimizationResult optimize(DeliveryPlan plan) {
        return optimize(plan, new ViaHubRunPolicy(properties.operationalPolicy(), properties.timeLimit()));
    }

    public ViaHubOptimizationResult optimize(DeliveryPlan plan, ViaHubRunPolicy runPolicy) {
        if (plan.pattern() != PlanningPattern.MULTI_CLIENT_MULTI_PICKUP_DELIVERY) {
            throw new OptimizationInputException(
                    "Le dispatch via le point de ralliement exige un plan avec plusieurs retraits.");
        }
        HubOptimizationOutcome outcome = hubOptimizationService.optimize(plan, runPolicy);
        return new ViaHubOptimizationResult(
                outcome.result(),
                outcome.hubExecution(),
                outcome.metrics(),
                snapshot(runPolicy.operationalPolicy()));
    }

    private OperationalPolicySnapshot snapshot(OperationalPolicy policy) {
        return new OperationalPolicySnapshot(
                policy.pickupServiceDuration().toSeconds(),
                policy.hubTransferDuration().toSeconds(),
                policy.deliveryServiceDuration().toSeconds(),
                policy.deliveryWindowEarlyTolerance().toSeconds(),
                policy.deliveryWindowLateTolerance().toSeconds(),
                "SERVICE_START");
    }
}
