package com.azylivraison.delivery.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.service.DeliveryRequestService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/delivery-requests")
public class ClientDeliveryRequestController {

    private final DeliveryRequestService service;

    public ClientDeliveryRequestController(DeliveryRequestService service) {
        this.service = service;
    }

    @GetMapping
    public List<DeliveryRequestResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.findForOwner(accountId(jwt));
    }

    @GetMapping("/{id}")
    public DeliveryRequestResponse get(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        return service.findForOwner(id, accountId(jwt));
    }

    @PostMapping
    public ResponseEntity<DeliveryRequestResponse> create(
            @Valid @RequestBody CreateDeliveryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        DeliveryRequestResponse created = service.create(accountId(jwt), request);
        return ResponseEntity
                .created(URI.create("/api/client/delivery-requests/" + created.id()))
                .body(created);
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }
}
