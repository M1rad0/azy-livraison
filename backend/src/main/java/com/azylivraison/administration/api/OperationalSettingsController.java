package com.azylivraison.administration.api;

import java.time.Instant;

import jakarta.validation.Valid;

import com.azylivraison.administration.domain.OperationalSettings;
import com.azylivraison.administration.service.OperationalSettingsService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/settings")
public class OperationalSettingsController {

    private final OperationalSettingsService service;

    public OperationalSettingsController(OperationalSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public OperationalSettingsResponse get() {
        return OperationalSettingsResponse.from(service.get());
    }

    @PutMapping
    public OperationalSettingsResponse update(@Valid @RequestBody OperationalSettingsUpdateRequest request) {
        OperationalSettings current = service.get();
        OperationalSettings requested = new OperationalSettings(
                request.dailyPackageLimit(),
                request.defaultHub() == null ? null : request.defaultHub().toDomain(),
                request.defaultPackageWeightKg(),
                request.pickupServiceMinutes(),
                request.hubServiceMinutes(),
                request.deliveryServiceMinutes(),
                request.earlyToleranceMinutes(),
                request.lateToleranceMinutes(),
                request.optimizationTimeLimitSeconds(),
                request.routingPreset(),
                request.defaultCourierCommissionNumerator() == null
                        ? current.defaultCourierCommissionNumerator()
                        : request.defaultCourierCommissionNumerator(),
                request.defaultCourierCommissionDenominator() == null
                        ? current.defaultCourierCommissionDenominator()
                        : request.defaultCourierCommissionDenominator(),
                request.defaultFuelPricePerLiterAriary() == null
                        ? current.defaultFuelPricePerLiterAriary()
                        : request.defaultFuelPricePerLiterAriary(),
                request.defaultExpressReturnToPickup() == null
                        ? current.defaultExpressReturnToPickup()
                        : request.defaultExpressReturnToPickup(),
                request.version() == null ? 0 : request.version(),
                Instant.EPOCH,
                Instant.EPOCH);
        return OperationalSettingsResponse.from(service.update(requested, request.version()));
    }
}
