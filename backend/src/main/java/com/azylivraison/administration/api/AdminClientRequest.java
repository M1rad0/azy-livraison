package com.azylivraison.administration.api;

import java.util.List;

import com.azylivraison.account.api.UpdateClientPayoutDestinationsRequest.Item;
import com.azylivraison.account.api.UpdateClientContactNumbersRequest;
import com.azylivraison.account.api.DefaultPickupRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminClientRequest(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 150) String displayName,
        @NotBlank @Size(max = 50) String phone,
        Boolean enabled,
        Boolean expressDeliveryEnabled,
        @Size(min = 8, max = 200) String temporaryPassword,
        Long version,
        @Size(max = 20) List<@NotNull @Valid Item> payoutDestinations,
        @Size(min = 1, max = 20) List<UpdateClientContactNumbersRequest.@NotNull @Valid Item> contactNumbers,
        @Valid DefaultPickupRequest defaultPickup,
        Boolean clearDefaultPickup) {

    public AdminClientRequest(String username, String displayName, String phone, Boolean enabled,
            Boolean expressDeliveryEnabled, String temporaryPassword, Long version, List<Item> payoutDestinations) {
        this(username, displayName, phone, enabled, expressDeliveryEnabled, temporaryPassword, version,
                payoutDestinations, null, null, null);
    }

    public AdminClientRequest(String username, String displayName, String phone, Boolean enabled,
            Boolean expressDeliveryEnabled, String temporaryPassword, Long version) {
        this(username, displayName, phone, enabled, expressDeliveryEnabled, temporaryPassword, version,
                null, null, null, null);
    }
}
