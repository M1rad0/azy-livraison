package com.azylivraison.account.api;

import java.util.UUID;

import com.azylivraison.account.domain.Account;
import com.azylivraison.account.domain.ClientProfile;
import com.azylivraison.account.domain.DefaultPickupLocation;

public record ClientProfileResponse(
        UUID accountId,
        String username,
        String displayName,
        String phone,
        DefaultPickupResponse defaultPickup,
        long version) {

    public static ClientProfileResponse from(Account account, ClientProfile profile) {
        return new ClientProfileResponse(
                account.id(),
                account.username(),
                account.displayName(),
                account.phone(),
                DefaultPickupResponse.from(profile.defaultPickup()),
                profile.version());
    }

    public record DefaultPickupResponse(
            String label,
            String destinationQuery,
            Double latitude,
            Double longitude) {

        private static DefaultPickupResponse from(DefaultPickupLocation pickup) {
            return pickup == null
                    ? null
                    : new DefaultPickupResponse(
                            pickup.label(),
                            pickup.query(),
                            pickup.latitude(),
                            pickup.longitude());
        }
    }
}
