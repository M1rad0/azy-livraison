package com.azylivraison.pricing.api;

import java.util.List;

import com.azylivraison.administration.api.AdvancedPricingPolicyResponse;
import com.azylivraison.delivery.api.AdvancedPricingQuoteResponse;
import com.azylivraison.delivery.api.DeliveryPricingQuoteRequest;
import com.azylivraison.pricing.service.AdvancedPricingService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/pricing")
public class PublicPricingController {

    private final AdvancedPricingService service;

    public PublicPricingController(AdvancedPricingService service) {
        this.service = service;
    }

    @GetMapping("/policies")
    public List<AdvancedPricingPolicyResponse> policies() {
        return service.defaultPolicies().stream().map(AdvancedPricingPolicyResponse::from).toList();
    }

    @PostMapping("/quote")
    public AdvancedPricingQuoteResponse quote(@Valid @RequestBody DeliveryPricingQuoteRequest request) {
        var destination = request.destination();
        return AdvancedPricingQuoteResponse.from(service.quote(null, request.requestKind(),
                destination.latitude(), destination.longitude(), request.weightKg(),
                request.desiredDeliveryTime(), request.desiredDeliveryEndTime(), request.deliveryTimePreference(),
                destination.label(), destination.query()));
    }
}
