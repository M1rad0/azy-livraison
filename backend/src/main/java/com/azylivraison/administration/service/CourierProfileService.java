package com.azylivraison.administration.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.azylivraison.administration.domain.CourierProfile;
import com.azylivraison.administration.domain.CourierPayoutDestination;
import com.azylivraison.administration.domain.VehiclePresetConfiguration;
import com.azylivraison.administration.repository.CourierProfileRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourierProfileService {

    private final CourierProfileRepository repository;
    private final VehiclePresetConfigurationService presetService;
    private final OperationalSettingsService settingsService;

    public CourierProfileService(
            CourierProfileRepository repository,
            VehiclePresetConfigurationService presetService,
            OperationalSettingsService settingsService) {
        this.repository = repository;
        this.presetService = presetService;
        this.settingsService = settingsService;
    }

    @Transactional(readOnly = true)
    public List<EffectiveCourierProfile> findAll() {
        return repository.findAll().stream().map(this::withEffectiveValues).toList();
    }

    @Transactional(readOnly = true)
    public EffectiveCourierProfile findById(UUID id) {
        return withEffectiveValues(findDomainById(id));
    }

    @Transactional
    public EffectiveCourierProfile create(CourierProfileCommand command) {
        presetService.findByType(command.vehicleType());
        String phoneNumber = normalizeOptional(firstNonBlank(command.phoneNumber(), command.contact()));
        CourierProfile courier = new CourierProfile(
                UUID.randomUUID(),
                command.accountId(),
                normalizeRequired(command.name()),
                phoneNumber,
                phoneNumber,
                normalizePayout(command.payoutDestination()),
                true,
                command.vehicleType(),
                command.maxWeightKg(),
                command.averageSpeedKmh(),
                reducedNumerator(command.commissionNumerator(), command.commissionDenominator()),
                reducedDenominator(command.commissionNumerator(), command.commissionDenominator()),
                command.defaultDepartureTime(),
                command.departureLocation(),
                0,
                Instant.EPOCH,
                Instant.EPOCH);
        repository.create(courier);
        return findById(courier.id());
    }

    @Transactional
    public EffectiveCourierProfile update(UUID id, CourierProfileCommand command, Long requestedVersion) {
        CourierProfile current = findDomainById(id);
        presetService.findByType(command.vehicleType());
        String phoneNumber = normalizeOptional(firstNonBlank(command.phoneNumber(), command.contact()));
        long expectedVersion = requestedVersion == null ? current.version() : requestedVersion;
        CourierProfile toSave = new CourierProfile(
                id,
                command.accountId(),
                normalizeRequired(command.name()),
                phoneNumber,
                phoneNumber,
                normalizePayout(command.payoutDestination()),
                command.active(),
                command.vehicleType(),
                command.maxWeightKg(),
                command.averageSpeedKmh(),
                reducedNumerator(command.commissionNumerator(), command.commissionDenominator()),
                reducedDenominator(command.commissionNumerator(), command.commissionDenominator()),
                command.defaultDepartureTime(),
                command.departureLocation(),
                current.version(),
                current.createdAt(),
                current.updatedAt());

        if (!repository.update(toSave, expectedVersion)) {
            throw versionConflict();
        }
        return findById(id);
    }

    @Transactional
    public EffectiveCourierProfile disable(UUID id, Long requestedVersion) {
        CourierProfile current = findDomainById(id);
        if (!current.active()) {
            return withEffectiveValues(current);
        }
        long expectedVersion = requestedVersion == null ? current.version() : requestedVersion;
        if (!repository.disable(id, expectedVersion)) {
            throw versionConflict();
        }
        return findById(id);
    }

    private CourierProfile findDomainById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new AdministrationNotFoundException(
                "COURIER_NOT_FOUND",
                "Le livreur demandé n'existe pas."));
    }

    private EffectiveCourierProfile withEffectiveValues(CourierProfile courier) {
        VehiclePresetConfiguration preset = presetService.findByType(courier.vehicleType());
        var settings = settingsService.get();
        return new EffectiveCourierProfile(
                courier,
                courier.maxWeightKg() == null ? preset.maxWeightKg() : courier.maxWeightKg(),
                courier.averageSpeedKmh() == null ? preset.averageSpeedKmh() : courier.averageSpeedKmh(),
                courier.commissionNumerator() == null
                        ? settings.defaultCourierCommissionNumerator()
                        : courier.commissionNumerator(),
                courier.commissionDenominator() == null
                        ? settings.defaultCourierCommissionDenominator()
                        : courier.commissionDenominator());
    }

    private Integer reducedNumerator(Integer numerator, Integer denominator) {
        return numerator == null || denominator == null ? null : numerator / greatestCommonDivisor(numerator, denominator);
    }

    private Integer reducedDenominator(Integer numerator, Integer denominator) {
        return numerator == null || denominator == null ? null : denominator / greatestCommonDivisor(numerator, denominator);
    }

    private int greatestCommonDivisor(int left, int right) {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    private String normalizeRequired(String value) {
        return value.trim();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private CourierPayoutDestination normalizePayout(CourierPayoutDestination payout) {
        if (payout == null) {
            return null;
        }
        return new CourierPayoutDestination(
                normalizeRequired(payout.operator()),
                normalizeRequired(payout.accountHolder()),
                normalizeRequired(payout.number()));
    }

    private AdministrationConflictException versionConflict() {
        return new AdministrationConflictException(
                "COURIER_VERSION_CONFLICT",
                "Ce livreur a été modifié entre-temps. Rechargez la page avant de réessayer.");
    }
}
