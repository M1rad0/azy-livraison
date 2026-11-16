package com.azylivraison.administration.api;

import java.util.List;
import java.util.UUID;

import com.azylivraison.account.domain.Account;
import com.azylivraison.account.domain.ClientProfile;
import com.azylivraison.account.domain.ClientPayoutDestination;
import com.azylivraison.account.domain.ClientContactNumber;
import com.azylivraison.account.domain.DefaultPickupLocation;

public record AdminClientResponse(
        UUID accountId,
        String username,
        String displayName,
        String phone,
        DefaultPickupResponse defaultPickup,
        boolean enabled,
        boolean expressDeliveryEnabled,
        long version,
        List<ClientPayoutDestination> payoutDestinations,
        List<ClientContactNumber> contactNumbers) {

    public static AdminClientResponse from(Account account, ClientProfile profile) {
        return new AdminClientResponse(
                account.id(), account.username(), account.displayName(), account.phone(),
                DefaultPickupResponse.from(profile.defaultPickup()), account.enabled(),
                profile.expressDeliveryEnabled(), profile.version(), profile.payoutDestinations(), profile.contactNumbers());
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
                            pickup.label(), pickup.query(), pickup.latitude(), pickup.longitude());
        }
    }
}
