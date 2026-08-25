package com.azylivraison.delivery.api;

import jakarta.validation.Valid;

import com.azylivraison.pricing.service.DeliveryPricingService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/delivery-pricing")
public class ClientDeliveryPricingController {

    private final DeliveryPricingService service;

    public ClientDeliveryPricingController(DeliveryPricingService service) {
        this.service = service;
    }

    @PostMapping("/quote")
    public DeliveryPricingQuoteResponse quote(
            @Valid @RequestBody DeliveryPricingQuoteRequest request) {
        ConfirmedLocationRequest destination = request.destination();
        return DeliveryPricingQuoteResponse.from(service.quote(
                destination.latitude(), destination.longitude()));
    }
}
