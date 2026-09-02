package com.azylivraison.delivery.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestStatus;
import com.azylivraison.delivery.service.DeliveryRequestService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin")
public class AdminDeliveryRequestController {

    private final DeliveryRequestService service;

    public AdminDeliveryRequestController(DeliveryRequestService service) {
        this.service = service;
    }

    @GetMapping("/delivery-requests")
    public List<DeliveryRequestResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DeliveryRequestStatus status,
            @RequestParam(required = false) String client,
            @RequestParam(defaultValue = "false") boolean pendingOnly) {
        return service.findForAdministration(date, from, to, status, client, pendingOnly);
    }

    @GetMapping("/queue")
    public AdminQueueResponse queue(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.queue(date);
    }

    @PostMapping("/clients/{clientAccountId}/delivery-requests")
    public DeliveryRequestResponse createForClient(
            @PathVariable UUID clientAccountId,
            @Valid @RequestBody CreateDeliveryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.createForAdministration(clientAccountId,
                UUID.fromString(jwt.getClaimAsString("accountId")), request);
    }
}
