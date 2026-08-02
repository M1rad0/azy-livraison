package com.azylivraison.planning.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.azylivraison.planning.domain.VehiclePreset;
import com.azylivraison.planning.domain.VehicleType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VehiclePresetService {

    private final Map<VehicleType, VehiclePreset> presets;

    public VehiclePresetService(
            @Value("${app.vehicle-presets.moto.max-weight-kg:30}") BigDecimal motoMaxWeightKg,
            @Value("${app.vehicle-presets.moto.average-speed-kmh:25}") BigDecimal motoAverageSpeedKmh,
            @Value("${app.vehicle-presets.bicycle.max-weight-kg:10}") BigDecimal bicycleMaxWeightKg,
            @Value("${app.vehicle-presets.bicycle.average-speed-kmh:12}") BigDecimal bicycleAverageSpeedKmh) {
        this.presets = Map.of(
                VehicleType.MOTO,
                new VehiclePreset(VehicleType.MOTO, motoMaxWeightKg, motoAverageSpeedKmh),
                VehicleType.BICYCLE,
                new VehiclePreset(VehicleType.BICYCLE, bicycleMaxWeightKg, bicycleAverageSpeedKmh));
    }

    public VehiclePreset get(VehicleType vehicleType) {
        return presets.get(vehicleType);
    }

    public List<VehiclePreset> getAll() {
        return presets.values().stream()
                .sorted((left, right) -> left.type().compareTo(right.type()))
                .toList();
    }
}

