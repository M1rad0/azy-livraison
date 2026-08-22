package com.azylivraison.account.api;

import com.azylivraison.account.service.ClientProfileService;
import com.azylivraison.account.service.ClientProfileService.ProfileSnapshot;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/profile")
public class ClientProfileController {

    private final ClientProfileService clientProfileService;

    public ClientProfileController(ClientProfileService clientProfileService) {
        this.clientProfileService = clientProfileService;
    }

    @GetMapping
    public ClientProfileResponse get(Authentication authentication) {
        return response(clientProfileService.get(authentication.getName()));
    }

    @PutMapping
    public ClientProfileResponse update(
            Authentication authentication,
            @Valid @RequestBody UpdateClientProfileRequest request) {
        return response(clientProfileService.update(
                authentication.getName(),
                request.displayName(),
                request.phone(),
                request.defaultPickup()));
    }

    private ClientProfileResponse response(ProfileSnapshot snapshot) {
        return ClientProfileResponse.from(snapshot.account(), snapshot.profile());
    }
}
