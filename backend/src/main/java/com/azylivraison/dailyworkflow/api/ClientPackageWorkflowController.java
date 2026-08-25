package com.azylivraison.dailyworkflow.api;

import java.util.UUID;

import com.azylivraison.dailyworkflow.service.DailyWorkflowService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/delivery-packages")
public class ClientPackageWorkflowController {

    private final DailyWorkflowService service;

    public ClientPackageWorkflowController(DailyWorkflowService service) {
        this.service = service;
    }

    @PostMapping("/{packageId}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID packageId,
            @Valid @RequestBody PackageVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        service.cancelForClient(packageId, request.version(), accountId(jwt));
        return ResponseEntity.noContent().build();
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }
}
