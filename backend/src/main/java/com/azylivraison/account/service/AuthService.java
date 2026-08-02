package com.azylivraison.account.service;

import java.time.Instant;

import com.azylivraison.account.domain.Account;
import com.azylivraison.account.repository.AccountRepository;
import com.azylivraison.account.repository.RefreshTokenRepository;
import com.azylivraison.account.service.TokenService.AccessToken;
import com.azylivraison.account.service.TokenService.RefreshToken;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final String dummyPasswordHash;

    public AuthService(
            AccountRepository accountRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService) {
        this.accountRepository = accountRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyPasswordHash = passwordEncoder.encode("invalid-password-used-for-timing");
    }

    @Transactional
    public AuthenticatedSession login(String username, String password) {
        Account account = accountRepository.findByUsername(username.trim()).orElse(null);
        String hashToCheck = account == null ? dummyPasswordHash : account.passwordHash();

        if (!passwordEncoder.matches(password, hashToCheck) || account == null || !account.enabled()) {
            throw new InvalidCredentialsException();
        }

        return createSession(account, Instant.now());
    }

    @Transactional
    public AuthenticatedSession refresh(String rawRefreshToken) {
        Instant now = Instant.now();
        String tokenHash = tokenService.hashRefreshToken(rawRefreshToken);
        RefreshTokenRepository.ActiveRefreshToken activeToken = refreshTokenRepository
                .findActiveForUpdate(tokenHash, now)
                .orElseThrow(InvalidRefreshTokenException::new);

        if (!activeToken.account().enabled()) {
            refreshTokenRepository.revoke(tokenHash, now);
            throw new InvalidRefreshTokenException();
        }

        RefreshToken replacement = tokenService.createRefreshToken(now);
        refreshTokenRepository.create(
                replacement.id(),
                activeToken.account().id(),
                replacement.hash(),
                replacement.expiresAt());
        refreshTokenRepository.rotate(activeToken.id(), replacement.id(), now);

        AccessToken accessToken = tokenService.createAccessToken(activeToken.account(), now);
        return new AuthenticatedSession(activeToken.account(), accessToken, replacement);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.revoke(tokenService.hashRefreshToken(rawRefreshToken), Instant.now());
    }

    @Transactional(readOnly = true)
    public Account currentAccount(String username) {
        return accountRepository.findByUsername(username)
                .filter(Account::enabled)
                .orElseThrow(InvalidCredentialsException::new);
    }

    private AuthenticatedSession createSession(Account account, Instant now) {
        AccessToken accessToken = tokenService.createAccessToken(account, now);
        RefreshToken refreshToken = tokenService.createRefreshToken(now);
        refreshTokenRepository.create(
                refreshToken.id(),
                account.id(),
                refreshToken.hash(),
                refreshToken.expiresAt());
        return new AuthenticatedSession(account, accessToken, refreshToken);
    }

    public record AuthenticatedSession(
            Account account,
            AccessToken accessToken,
            RefreshToken refreshToken) {
    }
}
