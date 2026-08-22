package com.azylivraison.dispatch.api;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.dispatch.domain.CreateDispatchCommand;
import com.azylivraison.dispatch.domain.DispatchPlanDetails;
import com.azylivraison.dispatch.service.CreateDispatchService;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dispatch-plans")
public class AdminDispatchController {

    private final CreateDispatchService service;

    public AdminDispatchController(CreateDispatchService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DispatchPlanResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateDispatchRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        DispatchPlanDetails created = service.create(new CreateDispatchCommand(
                request.serviceDate(),
                request.courierIds(),
                request.packageIds(),
                request.hubOverride() == null ? null : request.hubOverride().toDomain(),
                idempotencyKey,
                accountId(jwt)));
        return ResponseEntity
                .created(URI.create("/api/admin/dispatch-plans/" + created.plan().id()))
                .body(DispatchPlanResponse.from(created));
    }

    @GetMapping
    public List<DispatchPlanSummaryResponse> list(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {
        return service.list(date).stream()
                .map(details -> DispatchPlanSummaryResponse.from(details.plan()))
                .toList();
    }

    @GetMapping("/{id}")
    public DispatchPlanResponse get(@PathVariable UUID id) {
        return DispatchPlanResponse.from(service.get(id));
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }
}
