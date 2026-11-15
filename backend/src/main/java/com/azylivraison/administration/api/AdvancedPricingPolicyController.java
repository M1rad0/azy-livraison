package com.azylivraison.administration.api;

import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.pricing.service.AdvancedPricingService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/pricing-policies")
public class AdvancedPricingPolicyController {

    private final AdvancedPricingService service;

    public AdvancedPricingPolicyController(AdvancedPricingService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdvancedPricingPolicyResponse> get(@RequestParam(required = false) UUID clientAccountId) {
        return (clientAccountId == null ? service.defaultPolicies() : service.effectivePolicies(clientAccountId))
                .stream().map(AdvancedPricingPolicyResponse::from).toList();
    }

    @PutMapping
    public AdvancedPricingPolicyResponse save(@Valid @RequestBody AdvancedPricingPolicyRequest request) {
        return AdvancedPricingPolicyResponse.from(service.save(request.clientAccountId(), request.requestKind(),
                request.distanceMode(), request.centerDomain(), request.defaultWeightKg(), request.zoneDomains(),
                request.weightDomains(), request.timeDomains(), request.morningSurchargeAriary(),
                request.afternoonSurchargeAriary(), request.keywordDomains(), request.version()));
    }

    @DeleteMapping
    public ResponseEntity<Void> reset(@RequestParam UUID clientAccountId,
            @RequestParam DeliveryRequestKind requestKind) {
        service.resetClientPolicy(clientAccountId, requestKind);
        return ResponseEntity.noContent().build();
    }
}
