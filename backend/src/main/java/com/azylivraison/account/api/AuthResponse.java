package com.azylivraison.account.api;

import com.azylivraison.account.service.AuthService.AuthenticatedSession;

public record AuthResponse(
        String tokenType,
        String accessToken,
        long expiresIn,
        AccountResponse account) {

    public static AuthResponse from(AuthenticatedSession session) {
        return new AuthResponse(
                "Bearer",
                session.accessToken().value(),
                session.accessToken().expiresInSeconds(),
                AccountResponse.from(session.account()));
    }
}
