package com.azylivraison.dispatch.api;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.dispatch.domain.CreateDispatchCommand;
import com.azylivraison.dispatch.domain.DispatchCreationResult;
import com.azylivraison.dispatch.domain.DispatchPlanDetails;
import com.azylivraison.dispatch.domain.ManualDispatchAssignment;
import com.azylivraison.dispatch.service.CreateDispatchService;
import com.azylivraison.dispatch.service.DispatchPlanControlService;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dispatch-plans")
public class AdminDispatchController {

    private final CreateDispatchService service;
    private final DispatchPlanControlService controlService;

    public AdminDispatchController(
            CreateDispatchService service,
            DispatchPlanControlService controlService) {
        this.service = service;
        this.controlService = controlService;
    }

    @PostMapping
    public ResponseEntity<DispatchPlanResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateDispatchRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        DispatchCreationResult result = service.create(new CreateDispatchCommand(
                request.serviceDate(),
                request.courierIds(),
                request.hubOverride() == null ? null : request.hubOverride().toDomain(),
                idempotencyKey,
                accountId(jwt)));
        DispatchPlanDetails details = result.details();
        if (result.replayed()) {
            return ResponseEntity.ok(DispatchPlanResponse.from(details));
        }
        return ResponseEntity
                .created(URI.create("/api/admin/dispatch-plans/" + details.plan().id()))
                .body(DispatchPlanResponse.from(details));
    }

    @GetMapping
    public List<DispatchPlanSummaryResponse> list(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {
        return service.list(date).stream()
                .map(details -> DispatchPlanSummaryResponse.from(details.plan(), details.editable()))
                .toList();
    }

    @GetMapping("/{id}")
    public DispatchPlanResponse get(@PathVariable UUID id) {
        return DispatchPlanResponse.from(service.get(id));
    }

    @PostMapping("/{id}/cancel")
    public DispatchPlanResponse cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelDispatchRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return DispatchPlanResponse.from(controlService.cancel(
                id,
                request.expectedVersion(),
                request.reason(),
                accountId(jwt)));
    }

    @PostMapping("/{id}/regenerate")
    public ResponseEntity<DispatchPlanResponse> regenerate(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RegenerateDispatchRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        DispatchCreationResult result = controlService.regenerate(
                id,
                request.expectedVersion(),
                request.reason(),
                request.courierIds(),
                request.hubOverride() == null ? null : request.hubOverride().toDomain(),
                request.includeNewPending(),
                idempotencyKey,
                accountId(jwt));
        if (result.replayed()) {
            return ResponseEntity.ok(DispatchPlanResponse.from(result.details()));
        }
        return ResponseEntity
                .created(URI.create("/api/admin/dispatch-plans/" + result.details().plan().id()))
                .body(DispatchPlanResponse.from(result.details()));
    }

    @PutMapping("/{id}/manual-layout")
    public DispatchPlanResponse updateManualLayout(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateManualDispatchLayoutRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return DispatchPlanResponse.from(controlService.updateManualLayout(
                id,
                request.expectedVersion(),
                request.reason(),
                request.assignments().stream()
                        .map(assignment -> new ManualDispatchAssignment(
                                assignment.packageId(),
                                assignment.distributorCourierId(),
                                assignment.deliveryOrder()))
                        .toList(),
                accountId(jwt)));
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }
}
