package com.azylivraison.account.api;

import java.util.UUID;

import com.azylivraison.account.domain.Account;
import com.azylivraison.account.domain.AccountRole;

public record AccountResponse(
        UUID id,
        String username,
        AccountRole role,
        String displayName,
        String phone) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.id(),
                account.username(),
                account.role(),
                account.displayName(),
                account.phone());
    }
}
