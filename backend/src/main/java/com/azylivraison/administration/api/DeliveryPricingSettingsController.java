package com.azylivraison.administration.api;

import jakarta.validation.Valid;

import com.azylivraison.pricing.service.DeliveryPricingService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/settings/pricing")
public class DeliveryPricingSettingsController {

    private final DeliveryPricingService service;

    public DeliveryPricingSettingsController(DeliveryPricingService service) {
        this.service = service;
    }

    @GetMapping
    public DeliveryPricingPolicyResponse get() {
        return DeliveryPricingPolicyResponse.from(service.getPolicy());
    }

    @PutMapping
    public DeliveryPricingPolicyResponse update(
            @Valid @RequestBody DeliveryPricingPolicyUpdateRequest request) {
        return DeliveryPricingPolicyResponse.from(service.update(
                request.center().toDomain(),
                request.zones().stream().map(DeliveryPricingZoneRequest::toDomain).toList(),
                request.version()));
    }
}
