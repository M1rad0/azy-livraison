package com.azylivraison.account.domain;

import java.util.UUID;

public record ClientPayoutDestination(
        UUID id,
        String operator,
        String accountHolder,
        String number,
        int priority,
        boolean active) {
}
