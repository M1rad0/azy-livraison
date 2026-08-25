package com.azylivraison.account.domain;

import java.util.UUID;

public record ClientProfile(
        UUID accountId,
        DefaultPickupLocation defaultPickup,
        long version) {
}
