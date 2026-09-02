package com.azylivraison.account.api;

import java.util.List;
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
        boolean expressDeliveryEnabled,
        List<ContactNumberResponse> contactNumbers,
        List<PayoutDestinationResponse> payoutDestinations,
        long version) {

    public static ClientProfileResponse from(Account account, ClientProfile profile) {
        return new ClientProfileResponse(
                account.id(),
                account.username(),
                account.displayName(),
                account.phone(),
                DefaultPickupResponse.from(profile.defaultPickup()),
                profile.expressDeliveryEnabled(),
                profile.contactNumbers().stream().map(ContactNumberResponse::from).toList(),
                profile.payoutDestinations().stream().map(PayoutDestinationResponse::from).toList(),
                profile.version());
    }

    public record ContactNumberResponse(
            UUID id,
            String label,
            String number,
            int priority,
            boolean active) {

        private static ContactNumberResponse from(
                com.azylivraison.account.domain.ClientContactNumber contact) {
            return new ContactNumberResponse(
                    contact.id(), contact.label(), contact.number(), contact.priority(), contact.active());
        }
    }

    public record PayoutDestinationResponse(
            UUID id,
            String operator,
            String accountHolder,
            String number,
            int priority,
            boolean active) {

        private static PayoutDestinationResponse from(
                com.azylivraison.account.domain.ClientPayoutDestination destination) {
            return new PayoutDestinationResponse(
                    destination.id(), destination.operator(), destination.accountHolder(),
                    destination.number(), destination.priority(), destination.active());
        }
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
