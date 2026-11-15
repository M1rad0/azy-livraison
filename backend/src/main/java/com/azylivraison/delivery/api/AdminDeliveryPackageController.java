package com.azylivraison.delivery.api;

import java.util.UUID;
import com.azylivraison.delivery.service.DeliveryRequestService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/delivery-packages")
public class AdminDeliveryPackageController {
    private final DeliveryRequestService service;
    public AdminDeliveryPackageController(DeliveryRequestService service) { this.service = service; }

    @PutMapping("/{id}")
    public DeliveryRequestResponse update(@PathVariable UUID id,
            @Valid @RequestBody UpdateDeliveryPackageRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.updatePackage(id, null, UUID.fromString(jwt.getClaimAsString("accountId")), request);
    }

    @PostMapping("/{id}/reschedule")
    public DeliveryRequestResponse reschedule(@PathVariable UUID id,
            @Valid @RequestBody RescheduleDeliveryPackageRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.reschedulePackage(id, null, UUID.fromString(jwt.getClaimAsString("accountId")), request);
    }
}
