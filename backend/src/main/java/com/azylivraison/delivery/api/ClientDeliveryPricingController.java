package com.azylivraison.delivery.api;

import jakarta.validation.Valid;

import com.azylivraison.pricing.service.AdvancedPricingService;
import com.azylivraison.administration.api.AdvancedPricingPolicyResponse;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/delivery-pricing")
public class ClientDeliveryPricingController {

    private final AdvancedPricingService advancedPricingService;

    public ClientDeliveryPricingController(AdvancedPricingService advancedPricingService) {
        this.advancedPricingService = advancedPricingService;
    }

    @GetMapping("/policies")
    public List<AdvancedPricingPolicyResponse> policies(@AuthenticationPrincipal Jwt jwt) {
        UUID accountId = UUID.fromString(jwt.getClaimAsString("accountId"));
        return advancedPricingService.effectivePolicies(accountId).stream()
                .map(AdvancedPricingPolicyResponse::from).toList();
    }

    @PostMapping("/quote")
    public AdvancedPricingQuoteResponse quote(
            @Valid @RequestBody DeliveryPricingQuoteRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ConfirmedLocationRequest destination = request.destination();
        return AdvancedPricingQuoteResponse.from(advancedPricingService.quote(
                UUID.fromString(jwt.getClaimAsString("accountId")), request.requestKind(),
                destination.latitude(), destination.longitude(), request.weightKg(),
                request.desiredDeliveryTime()));
    }
}
