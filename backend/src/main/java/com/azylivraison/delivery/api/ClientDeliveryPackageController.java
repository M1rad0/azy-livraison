package com.azylivraison.delivery.api;

import java.util.UUID;
import com.azylivraison.delivery.service.DeliveryRequestService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/client/delivery-packages")
public class ClientDeliveryPackageController {
    private final DeliveryRequestService service;
    public ClientDeliveryPackageController(DeliveryRequestService service) { this.service = service; }

    @PutMapping("/{id}")
    public DeliveryRequestResponse update(@PathVariable UUID id,
            @Valid @RequestBody UpdateDeliveryPackageRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID actor = UUID.fromString(jwt.getClaimAsString("accountId"));
        return service.updatePackage(id, actor, actor, request);
    }

    @PostMapping("/{id}/reschedule")
    public DeliveryRequestResponse reschedule(@PathVariable UUID id,
            @Valid @RequestBody RescheduleDeliveryPackageRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID actor = UUID.fromString(jwt.getClaimAsString("accountId"));
        return service.reschedulePackage(id, actor, actor, request);
    }
}
