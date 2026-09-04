package com.azylivraison.administration.api;

import java.util.List;
import java.util.UUID;

import com.azylivraison.administration.service.AdminClientService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/clients")
public class AdminClientController {

    private final AdminClientService service;

    public AdminClientController(AdminClientService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminClientResponse> list() {
        return service.findAll();
    }

    @PostMapping
    public AdminClientCreatedResponse create(@Valid @RequestBody AdminClientRequest request) {
        var result = service.create(request);
        return new AdminClientCreatedResponse(result.client(), result.temporaryPassword());
    }

    @PutMapping("/{accountId}")
    public AdminClientResponse update(@PathVariable UUID accountId,
            @Valid @RequestBody AdminClientRequest request) {
        return service.update(accountId, request);
    }

    @PostMapping("/{accountId}/reset-password")
    public TemporaryPasswordResponse resetPassword(@PathVariable UUID accountId) {
        return new TemporaryPasswordResponse(service.resetPassword(accountId));
    }

    public record AdminClientCreatedResponse(AdminClientResponse client, String temporaryPassword) {}
}
