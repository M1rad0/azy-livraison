package com.azylivraison.administration.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

import com.azylivraison.administration.domain.VehiclePresetConfiguration;
import com.azylivraison.administration.repository.VehiclePresetConfigurationRepository;
import com.azylivraison.planning.domain.VehicleType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehiclePresetConfigurationService {

    private final VehiclePresetConfigurationRepository repository;

    public VehiclePresetConfigurationService(VehiclePresetConfigurationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<VehiclePresetConfiguration> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public VehiclePresetConfiguration findByType(VehicleType vehicleType) {
        return repository.findByType(vehicleType).orElseThrow(() -> new AdministrationNotFoundException(
                "VEHICLE_PRESET_NOT_FOUND",
                "Le préréglage de véhicule demandé n'existe pas."));
    }

    @Transactional
    public VehiclePresetConfiguration update(
            VehicleType vehicleType,
            BigDecimal maxWeightKg,
            BigDecimal averageSpeedKmh,
            Long requestedVersion) {
        VehiclePresetConfiguration current = findByType(vehicleType);
        long expectedVersion = requestedVersion == null ? current.version() : requestedVersion;
        VehiclePresetConfiguration toSave = new VehiclePresetConfiguration(
                vehicleType,
                maxWeightKg,
                averageSpeedKmh,
                current.version(),
                current.createdAt(),
                current.updatedAt());

        if (!repository.update(toSave, expectedVersion)) {
            throw new AdministrationConflictException(
                    "VEHICLE_PRESET_VERSION_CONFLICT",
                    "Ce préréglage a été modifié entre-temps. Rechargez la page avant de réessayer.");
        }
        return findByType(vehicleType);
    }

    @Transactional
    public List<VehiclePresetConfiguration> updateAll(List<VehiclePresetUpdateCommand> commands) {
        HashSet<VehicleType> updatedTypes = new HashSet<>();
        for (VehiclePresetUpdateCommand command : commands) {
            if (!updatedTypes.add(command.vehicleType())) {
                throw new AdministrationConflictException(
                        "DUPLICATE_VEHICLE_PRESET",
                        "Un même type de véhicule ne peut être envoyé qu'une seule fois.");
            }
            update(
                    command.vehicleType(),
                    command.maxWeightKg(),
                    command.averageSpeedKmh(),
                    command.version());
        }
        return findAll();
    }
}
