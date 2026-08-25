package com.azylivraison.account.domain;

import java.util.List;
import java.util.UUID;

public record ClientProfile(
        UUID accountId,
        DefaultPickupLocation defaultPickup,
        List<ClientContactNumber> contactNumbers,
        List<ClientPayoutDestination> payoutDestinations,
        long version) {

    public ClientProfile {
        contactNumbers = List.copyOf(contactNumbers);
        payoutDestinations = List.copyOf(payoutDestinations);
    }
}
