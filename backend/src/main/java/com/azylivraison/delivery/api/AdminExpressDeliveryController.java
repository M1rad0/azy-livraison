package com.azylivraison.delivery.api;

import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.service.ExpressDeliveryService;
import com.azylivraison.delivery.service.ExpressFuelService;
import com.azylivraison.delivery.domain.ExpressFuelAdjustment;
import com.azylivraison.dailyworkflow.api.PackageVersionRequest;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/express-deliveries")
public class AdminExpressDeliveryController {

    private final ExpressDeliveryService service;
    private final ExpressFuelService fuelService;

    public AdminExpressDeliveryController(ExpressDeliveryService service, ExpressFuelService fuelService) {
        this.service = service;
        this.fuelService = fuelService;
    }

    @GetMapping
    public List<ExpressDeliveryResponse> list() { return service.list(); }

    @PostMapping("/{id}/confirm")
    public ExpressDeliveryResponse confirm(@PathVariable UUID id,
            @Valid @RequestBody ConfirmExpressDeliveryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.confirm(id, request.courierIds(), request.returnToPickup(),
                request.version(), accountId(jwt));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id,
            @Valid @RequestBody PackageVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        service.cancel(id, request.version(), accountId(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/fuel")
    public ExpressFuelResponse fuel(@PathVariable UUID id) {
        return ExpressFuelResponse.from(fuelService.get(id));
    }

    @PostMapping("/{id}/fuel/recalculate")
    public ExpressFuelResponse recalculateFuel(@PathVariable UUID id,
            @Valid @RequestBody PackageVersionRequest request) {
        return ExpressFuelResponse.from(fuelService.recalculate(id, request.version()));
    }

    @PutMapping("/{id}/fuel")
    public ExpressFuelResponse adjustFuel(@PathVariable UUID id,
            @Valid @RequestBody UpdateExpressFuelRequest request) {
        return ExpressFuelResponse.from(fuelService.adjust(
                id, request.version(), request.lines().stream().map(line -> new ExpressFuelAdjustment(
                        line.courierId(), line.finalDistanceMeters(), line.finalFuelAmountAriary(),
                        line.reason())).toList()));
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }
}
