package com.azylivraison.dailyworkflow.api;

import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.dailyworkflow.service.ClientClosureService;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery-days/{serviceDate}")
public class ClientClosureController {

    private final ClientClosureService service;

    public ClientClosureController(ClientClosureService service) {
        this.service = service;
    }

    @GetMapping("/client-closures")
    public ClientClosureResponse overview(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate) {
        return ClientClosureResponse.from(service.overview(serviceDate));
    }

    @PostMapping("/clients/{clientAccountId}/close")
    public ClientClosureResponse close(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID clientAccountId,
            @Valid @RequestBody PackageVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ClientClosureResponse.from(service.close(
                serviceDate, clientAccountId, request.version(), accountId(jwt)));
    }

    @PostMapping("/clients/{clientAccountId}/reopen")
    public ClientClosureResponse reopen(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID clientAccountId,
            @Valid @RequestBody ReopenClientRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ClientClosureResponse.from(service.reopen(
                serviceDate, clientAccountId, request.version(), request.reason(), accountId(jwt)));
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }
}
