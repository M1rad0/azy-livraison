package com.azylivraison.account.domain;

import java.util.UUID;

public record ClientContactNumber(
        UUID id,
        String label,
        String number,
        int priority,
        boolean active) {
}
