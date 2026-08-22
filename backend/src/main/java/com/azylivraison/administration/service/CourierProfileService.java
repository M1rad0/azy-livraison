package com.azylivraison.administration.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.azylivraison.administration.domain.CourierProfile;
import com.azylivraison.administration.domain.VehiclePresetConfiguration;
import com.azylivraison.administration.repository.CourierProfileRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourierProfileService {

    private final CourierProfileRepository repository;
    private final VehiclePresetConfigurationService presetService;

    public CourierProfileService(
            CourierProfileRepository repository,
            VehiclePresetConfigurationService presetService) {
        this.repository = repository;
        this.presetService = presetService;
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
        CourierProfile courier = new CourierProfile(
                UUID.randomUUID(),
                command.accountId(),
                normalizeRequired(command.name()),
                normalizeOptional(command.contact()),
                true,
                command.vehicleType(),
                command.maxWeightKg(),
                command.averageSpeedKmh(),
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
        long expectedVersion = requestedVersion == null ? current.version() : requestedVersion;
        CourierProfile toSave = new CourierProfile(
                id,
                command.accountId(),
                normalizeRequired(command.name()),
                normalizeOptional(command.contact()),
                command.active(),
                command.vehicleType(),
                command.maxWeightKg(),
                command.averageSpeedKmh(),
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
        return new EffectiveCourierProfile(
                courier,
                courier.maxWeightKg() == null ? preset.maxWeightKg() : courier.maxWeightKg(),
                courier.averageSpeedKmh() == null ? preset.averageSpeedKmh() : courier.averageSpeedKmh());
    }

    private String normalizeRequired(String value) {
        return value.trim();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AdministrationConflictException versionConflict() {
        return new AdministrationConflictException(
                "COURIER_VERSION_CONFLICT",
                "Ce livreur a été modifié entre-temps. Rechargez la page avant de réessayer.");
    }
}
