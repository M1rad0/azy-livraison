package com.azylivraison.administration.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.azylivraison.administration.service.CourierProfileCommand;
import com.azylivraison.administration.service.CourierProfileService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/couriers")
public class CourierProfileController {

    private final CourierProfileService service;

    public CourierProfileController(CourierProfileService service) {
        this.service = service;
    }

    @GetMapping
    public List<CourierResponse> findAll() {
        return service.findAll().stream().map(CourierResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<CourierResponse> create(@Valid @RequestBody CourierCreateRequest request) {
        CourierResponse response = CourierResponse.from(service.create(new CourierProfileCommand(
                request.accountId(),
                request.name(),
                request.contact(),
                true,
                request.vehicleType(),
                request.maxWeightKg(),
                request.averageSpeedKmh(),
                request.defaultDepartureTime(),
                request.departureLocation().toDomain())));
        return ResponseEntity.created(URI.create("/api/admin/couriers/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public CourierResponse findById(@PathVariable UUID id) {
        return CourierResponse.from(service.findById(id));
    }

    @PutMapping("/{id}")
    public CourierResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody CourierUpdateRequest request) {
        return CourierResponse.from(service.update(id, new CourierProfileCommand(
                request.accountId(),
                request.name(),
                request.contact(),
                request.active(),
                request.vehicleType(),
                request.maxWeightKg(),
                request.averageSpeedKmh(),
                request.defaultDepartureTime(),
                request.departureLocation().toDomain()), request.version()));
    }

    @RequestMapping(path = "/{id}/disable", method = {RequestMethod.POST, RequestMethod.PATCH})
    public CourierResponse disable(
            @PathVariable UUID id,
            @RequestParam(required = false) Long version) {
        return CourierResponse.from(service.disable(id, version));
    }
}
