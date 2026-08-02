package com.azylivraison.account.api;

import com.azylivraison.account.service.AuthService;
import com.azylivraison.account.service.AuthService.AuthenticatedSession;
import com.azylivraison.account.service.InvalidRefreshTokenException;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookieService refreshCookieService;

    public AuthController(AuthService authService, RefreshCookieService refreshCookieService) {
        this.authService = authService;
        this.refreshCookieService = refreshCookieService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthenticatedSession session = authService.login(request.username(), request.password());
        return withRefreshCookie(session);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = RefreshCookieService.COOKIE_NAME, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        return withRefreshCookie(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookieService.COOKIE_NAME, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookieService.clear().toString())
                .build();
    }

    @GetMapping("/me")
    public AccountResponse me(Authentication authentication) {
        return AccountResponse.from(authService.currentAccount(authentication.getName()));
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(AuthenticatedSession session) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookieService.create(session.refreshToken().value()).toString())
                .body(AuthResponse.from(session));
    }
}
