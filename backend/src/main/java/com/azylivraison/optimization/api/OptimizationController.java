package com.azylivraison.optimization.api;

import com.azylivraison.optimization.domain.OptimizationComparisonResult;
import com.azylivraison.optimization.domain.OptimizationResult;
import com.azylivraison.optimization.service.OptimizationComparisonService;
import com.azylivraison.optimization.service.OptimizationService;
import com.azylivraison.planning.api.DeliveryPlanRequest;
import com.azylivraison.planning.domain.DeliveryPlan;
import com.azylivraison.planning.service.PlanValidationService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plans")
public class OptimizationController {

    private final PlanValidationService planValidationService;
    private final OptimizationService optimizationService;
    private final OptimizationComparisonService optimizationComparisonService;

    public OptimizationController(
            PlanValidationService planValidationService,
            OptimizationService optimizationService,
            OptimizationComparisonService optimizationComparisonService) {
        this.planValidationService = planValidationService;
        this.optimizationService = optimizationService;
        this.optimizationComparisonService = optimizationComparisonService;
    }

    @PostMapping("/optimize")
    public ResponseEntity<OptimizationResult> optimize(
            @Valid @RequestBody DeliveryPlanRequest request) {
        DeliveryPlan plan = planValidationService.validateAndNormalize(request);
        return ResponseEntity.ok(optimizationService.optimize(plan));
    }

    @PostMapping("/compare")
    public ResponseEntity<OptimizationComparisonResult> compare(
            @Valid @RequestBody DeliveryPlanRequest request) {
        DeliveryPlan plan = planValidationService.validateAndNormalize(request);
        return ResponseEntity.ok(optimizationComparisonService.compare(plan));
    }
}
