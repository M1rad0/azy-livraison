package com.azylivraison.account.domain;

import java.util.UUID;

public record Account(
        UUID id,
        String username,
        String passwordHash,
        boolean enabled,
        AccountRole role,
        String displayName,
        String phone) {
}
