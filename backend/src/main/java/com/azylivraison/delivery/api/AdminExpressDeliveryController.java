package com.azylivraison.delivery.api;

import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.service.ExpressDeliveryService;
import com.azylivraison.dailyworkflow.api.PackageVersionRequest;

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
@RequestMapping("/api/admin/express-deliveries")
public class AdminExpressDeliveryController {

    private final ExpressDeliveryService service;

    public AdminExpressDeliveryController(ExpressDeliveryService service) {
        this.service = service;
    }

    @GetMapping
    public List<ExpressDeliveryResponse> list() { return service.list(); }

    @PostMapping("/{id}/confirm")
    public ExpressDeliveryResponse confirm(@PathVariable UUID id,
            @Valid @RequestBody ConfirmExpressDeliveryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.confirm(id, request.courierIds(), request.version(), accountId(jwt));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id,
            @Valid @RequestBody PackageVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        service.cancel(id, request.version(), accountId(jwt));
        return ResponseEntity.noContent().build();
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }
}
