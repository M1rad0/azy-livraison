package com.azylivraison.account.api;

import java.time.Duration;

import com.azylivraison.account.config.AuthProperties;
import com.azylivraison.account.service.TokenService;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class RefreshCookieService {

    public static final String COOKIE_NAME = "azy_refresh_token";

    private final AuthProperties properties;
    private final TokenService tokenService;

    public RefreshCookieService(AuthProperties properties, TokenService tokenService) {
        this.properties = properties;
        this.tokenService = tokenService;
    }

    public ResponseCookie create(String token) {
        return baseCookie(token)
                .maxAge(tokenService.refreshTokenTtl())
                .build();
    }

    public ResponseCookie clear() {
        return baseCookie("")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path("/api/auth");
    }
}
