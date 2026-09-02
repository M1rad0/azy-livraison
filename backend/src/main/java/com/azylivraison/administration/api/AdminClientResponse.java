package com.azylivraison.administration.api;

import java.util.UUID;

import com.azylivraison.account.domain.Account;
import com.azylivraison.account.domain.ClientProfile;

public record AdminClientResponse(
        UUID accountId,
        String username,
        String displayName,
        String phone,
        boolean enabled,
        boolean expressDeliveryEnabled,
        long version) {

    public static AdminClientResponse from(Account account, ClientProfile profile) {
        return new AdminClientResponse(
                account.id(), account.username(), account.displayName(), account.phone(),
                account.enabled(), profile.expressDeliveryEnabled(), profile.version());
    }
}
