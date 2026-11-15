package com.azylivraison.dispatch.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.dispatch.domain.CreateDispatchCommand;
import com.azylivraison.dispatch.domain.ManualDispatchDraft;
import com.azylivraison.dispatch.domain.ManualDispatchDraft.DraftCourier;
import com.azylivraison.dispatch.domain.ManualDispatchDraft.Collection;
import com.azylivraison.dispatch.domain.ManualDispatchDraft.Delivery;
import com.azylivraison.dispatch.service.ManualDispatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/manual-dispatch-drafts")
public class ManualDispatchController {
    private final ManualDispatchService service;

    public ManualDispatchController(ManualDispatchService service) { this.service = service; }

    @PostMapping
    public ManualDispatchDraft create(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateDispatchRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.create(new CreateDispatchCommand(request.serviceDate(), request.courierIds(),
                request.hubOverride() == null ? null : request.hubOverride().toDomain(), key, actor(jwt)));
    }

    @GetMapping("/active")
    public ResponseEntity<ManualDispatchDraft> active(@RequestParam LocalDate date) {
        return service.active(date).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/for-plan/{id}")
    public ResponseEntity<ManualDispatchDraft> forPlan(@PathVariable UUID id) {
        return service.forPlan(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public ManualDispatchDraft get(@PathVariable UUID id) { return service.get(id); }

    @PutMapping("/{id}")
    public ManualDispatchDraft save(@PathVariable UUID id, @Valid @RequestBody Layout request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.save(id, request.expectedVersion(), request.step(), request.couriers(),
                request.collections(), request.deliveries(), actor(jwt));
    }

    @PostMapping("/{id}/finalize")
    public DispatchPlanResponse finalizeDraft(@PathVariable UUID id, @Valid @RequestBody Version request,
            @AuthenticationPrincipal Jwt jwt) {
        return DispatchPlanResponse.from(service.finalizeDraft(id, request.expectedVersion(), actor(jwt)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, @Valid @RequestBody Version request,
            @AuthenticationPrincipal Jwt jwt) {
        service.cancel(id, request.expectedVersion(), actor(jwt));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/from-plan/{id}")
    public ManualDispatchDraft edit(@PathVariable UUID id, @Valid @RequestBody Version request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.edit(id, request.expectedVersion(), actor(jwt));
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("accountId")); }

    public record Version(@NotNull @Min(0) Long expectedVersion) { }
    public record Layout(@NotNull @Min(0) Long expectedVersion, @NotNull ManualDispatchDraft.Step step,
            @NotNull List<@NotNull DraftCourier> couriers,
            @NotNull List<@NotNull Collection> collections,
            @NotNull List<@NotNull Delivery> deliveries) { }
}
