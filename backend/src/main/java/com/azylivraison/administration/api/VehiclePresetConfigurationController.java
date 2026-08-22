package com.azylivraison.administration.api;

import java.util.List;

import jakarta.validation.Valid;

import com.azylivraison.administration.service.VehiclePresetConfigurationService;
import com.azylivraison.administration.service.VehiclePresetUpdateCommand;
import com.azylivraison.planning.domain.VehicleType;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({
        "/api/admin/vehicle-presets",
        "/api/admin/settings/presets",
        "/api/admin/settings/vehicle-presets"
})
public class VehiclePresetConfigurationController {

    private final VehiclePresetConfigurationService service;

    public VehiclePresetConfigurationController(VehiclePresetConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    public List<VehiclePresetResponse> findAll() {
        return service.findAll().stream().map(VehiclePresetResponse::from).toList();
    }

    @PutMapping
    public List<VehiclePresetResponse> updateAll(
            @Valid @RequestBody List<@Valid VehiclePresetBulkUpdateRequest> requests) {
        List<VehiclePresetUpdateCommand> commands = requests.stream()
                .map(request -> new VehiclePresetUpdateCommand(
                        request.vehicleType(),
                        request.maxWeightKg(),
                        request.averageSpeedKmh(),
                        request.version()))
                .toList();
        return service.updateAll(commands).stream().map(VehiclePresetResponse::from).toList();
    }

    @PutMapping("/{vehicleType}")
    public VehiclePresetResponse update(
            @PathVariable VehicleType vehicleType,
            @Valid @RequestBody VehiclePresetUpdateRequest request) {
        return VehiclePresetResponse.from(service.update(
                vehicleType,
                request.maxWeightKg(),
                request.averageSpeedKmh(),
                request.version()));
    }
}
