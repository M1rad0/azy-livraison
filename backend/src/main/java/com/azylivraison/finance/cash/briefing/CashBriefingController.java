package com.azylivraison.finance.cash.briefing;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.azylivraison.finance.cash.briefing.CashBriefingModels.*;

@RestController
@RequestMapping("/api/admin/cash-briefings")
public class CashBriefingController {
    private final CashBriefingService service;
    public CashBriefingController(CashBriefingService service) { this.service = service; }
    @GetMapping public List<Batch> list(@RequestParam(required = false) UUID planId) { return service.list(planId); }
    @GetMapping("/planning") public Planning planning(@RequestParam UUID planId) { return service.planning(planId); }
    @PostMapping("/manual") public Batch manual(@Valid @RequestBody ManualRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.manual(request, UUID.fromString(jwt.getClaimAsString("accountId")));
    }
    @PostMapping("/prepare") public Batch prepare(@Valid @RequestBody PrepareRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.prepare(request, UUID.fromString(jwt.getClaimAsString("accountId")));
    }
    @PostMapping("/{id}/release") public Batch release(@PathVariable UUID id) { return service.release(id); }
    @PostMapping("/{id}/revalidate") public Batch revalidate(@PathVariable UUID id) { return service.revalidate(id); }
    @PostMapping(path = "/transfers/{id}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Batch confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.confirm(id, request, UUID.fromString(jwt.getClaimAsString("accountId")), null);
    }
    @PostMapping(path = "/transfers/{id}/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Batch confirmWithProof(@PathVariable UUID id, @Valid @RequestPart("transfer") ConfirmRequest request,
            @RequestPart(value = "proof", required = false) MultipartFile proof, @AuthenticationPrincipal Jwt jwt) {
        return service.confirm(id, request, UUID.fromString(jwt.getClaimAsString("accountId")), proof);
    }
}
