package com.azylivraison.planning.api;

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
public class PlanValidationController {

    private final PlanValidationService planValidationService;

    public PlanValidationController(PlanValidationService planValidationService) {
        this.planValidationService = planValidationService;
    }

    @PostMapping("/validate")
    public ResponseEntity<PlanValidationResponse> validate(
            @Valid @RequestBody DeliveryPlanRequest request) {
        DeliveryPlan plan = planValidationService.validateAndNormalize(request);
        return ResponseEntity.ok(PlanValidationResponse.success(plan));
    }
}

