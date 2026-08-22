package com.azylivraison.optimization.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.azylivraison.optimization.config.OptimizationProperties;
import com.azylivraison.optimization.domain.OperationalPolicy;
import com.azylivraison.optimization.domain.OptimizationComparisonResult;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OperationalPolicySnapshot;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OptimizationScenario;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OptimizationStrategy;
import com.azylivraison.optimization.domain.OptimizationResult;
import com.azylivraison.planning.domain.DeliveryPlan;
import com.azylivraison.planning.domain.PlanningPattern;

import org.springframework.stereotype.Service;

@Service
public class OptimizationComparisonService {

    private static final String COMPARISON_SCHEMA_VERSION = "1.0";
    private static final ZoneId ANTANANARIVO = ZoneId.of("Indian/Antananarivo");

    private final OptimizationService directOptimizationService;
    private final HubOptimizationService hubOptimizationService;
    private final OptimizationScenarioEvaluator evaluator;
    private final OptimizationScenarioComparator comparator;
    private final OptimizationProperties properties;

    public OptimizationComparisonService(
            OptimizationService directOptimizationService,
            HubOptimizationService hubOptimizationService,
            OptimizationScenarioEvaluator evaluator,
            OptimizationScenarioComparator comparator,
            OptimizationProperties properties) {
        this.directOptimizationService = directOptimizationService;
        this.hubOptimizationService = hubOptimizationService;
        this.evaluator = evaluator;
        this.comparator = comparator;
        this.properties = properties;
    }

    public OptimizationComparisonResult compare(DeliveryPlan plan) {
        if (plan.pattern() != PlanningPattern.MULTI_CLIENT_MULTI_PICKUP_DELIVERY) {
            throw new OptimizationInputException(
                    "La comparaison avec un siège est disponible uniquement pour le mode plusieurs retraits.");
        }
        if (plan.redistributionHub() == null
                || plan.redistributionHub().latitude() == null
                || plan.redistributionHub().longitude() == null) {
            throw new OptimizationInputException(
                    "Le siège doit être confirmé sur la carte avant de comparer les deux options.");
        }

        OperationalPolicy policy = properties.operationalPolicy();
        OptimizationResult directResult = directOptimizationService.optimizeForComparison(plan);
        var directMetrics = evaluator.evaluate(plan, directResult, policy);
        HubOptimizationOutcome hub = hubOptimizationService.optimize(plan);
        var decision = comparator.compare(directMetrics, hub.metrics());

        return new OptimizationComparisonResult(
                COMPARISON_SCHEMA_VERSION,
                plan.planId(),
                OffsetDateTime.now(ANTANANARIVO),
                decision.strategy(),
                decision.criterion(),
                decision.reason(),
                snapshot(policy),
                new OptimizationScenario(OptimizationStrategy.DIRECT, directResult, directMetrics),
                new OptimizationScenario(OptimizationStrategy.VIA_HUB, hub.result(), hub.metrics()),
                decision.delta(),
                hub.hubExecution());
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
