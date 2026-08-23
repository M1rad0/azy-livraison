package com.azylivraison.dailyworkflow.api;

import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.dailyworkflow.repository.DailyWorkflowRepository.CarryForwardIds;
import com.azylivraison.dailyworkflow.service.DailyWorkflowService;
import com.azylivraison.delivery.domain.ConfirmedLocation;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminDailyWorkflowController {

    private final DailyWorkflowService service;

    public AdminDailyWorkflowController(DailyWorkflowService service) {
        this.service = service;
    }

    @GetMapping("/delivery-days/{serviceDate}")
    public DailyOperationResponse get(
            @PathVariable
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate serviceDate) {
        return DailyOperationResponse.from(service.get(serviceDate));
    }

    @PostMapping("/delivery-packages/{packageId}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID packageId,
            @Valid @RequestBody PackageVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        service.cancelForAdministration(packageId, request.version(), accountId(jwt));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/delivery-packages/{packageId}/delivery-fee")
    public DeliveryFeeResponse updateDeliveryFee(
            @PathVariable UUID packageId,
            @Valid @RequestBody DeliveryFeeUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return DeliveryFeeResponse.from(service.updateDeliveryFee(
                packageId,
                request.mode(),
                request.manualFeeAriary(),
                request.reason(),
                request.version(),
                accountId(jwt)));
    }

    @PutMapping("/delivery-days/{serviceDate}/packages/{packageId}/outcome")
    public DailyOperationResponse recordOutcome(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID packageId,
            @Valid @RequestBody OutcomeUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return DailyOperationResponse.from(service.recordOutcome(
                serviceDate,
                packageId,
                request.outcome(),
                request.actualCollectedAriary(),
                request.actualCourierId(),
                request.note(),
                request.version(),
                accountId(jwt)));
    }

    @PostMapping("/delivery-days/{serviceDate}/packages/{packageId}/return-completed")
    public DailyOperationResponse markReturnCompleted(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID packageId,
            @Valid @RequestBody PackageVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return DailyOperationResponse.from(service.markReturnCompleted(
                serviceDate, packageId, request.version(), accountId(jwt)));
    }

    @PostMapping("/delivery-days/{serviceDate}/packages/{packageId}/carry-forward")
    public CarryForwardResponse carryForward(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID packageId,
            @Valid @RequestBody CarryForwardRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        var pickup = request.pickup();
        CarryForwardIds ids = service.carryForward(
                serviceDate,
                packageId,
                new ConfirmedLocation(
                        pickup.label().trim(),
                        pickup.query() == null || pickup.query().isBlank() ? null : pickup.query().trim(),
                        pickup.latitude(),
                        pickup.longitude()),
                request.version(),
                accountId(jwt));
        return CarryForwardResponse.from(ids);
    }

    @PostMapping("/delivery-days/{serviceDate}/close")
    public DailyOperationResponse close(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @Valid @RequestBody PackageVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return DailyOperationResponse.from(service.close(serviceDate, request.version(), accountId(jwt)));
    }

    @PostMapping("/delivery-days/{serviceDate}/reopen")
    public DailyOperationResponse reopen(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @Valid @RequestBody ReopenDayRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return DailyOperationResponse.from(service.reopen(
                serviceDate, request.version(), request.reason(), accountId(jwt)));
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }
}
