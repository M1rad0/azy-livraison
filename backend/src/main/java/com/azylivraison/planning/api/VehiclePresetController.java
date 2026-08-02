package com.azylivraison.planning.api;

import java.util.List;

import com.azylivraison.planning.domain.VehiclePreset;
import com.azylivraison.planning.service.VehiclePresetService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicle-presets")
public class VehiclePresetController {

    private final VehiclePresetService vehiclePresetService;

    public VehiclePresetController(VehiclePresetService vehiclePresetService) {
        this.vehiclePresetService = vehiclePresetService;
    }

    @GetMapping
    public List<VehiclePreset> getAll() {
        return vehiclePresetService.getAll();
    }
}

