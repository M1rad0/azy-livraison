package com.azylivraison.account.api;

import java.util.UUID;

import com.azylivraison.account.domain.Account;

public record AccountResponse(UUID id, String username) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.id(), account.username());
    }
}
