package com.azylivraison.account.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.account.domain.Account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public RefreshTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(UUID id, UUID accountId, String tokenHash, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO refresh_token (id, account_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?)
                """, id, accountId, tokenHash, Timestamp.from(expiresAt));
    }

    public Optional<ActiveRefreshToken> findActiveForUpdate(String tokenHash, Instant now) {
        List<ActiveRefreshToken> tokens = jdbcTemplate.query("""
                SELECT rt.id, a.id AS account_id, a.username, a.password_hash, a.enabled
                FROM refresh_token rt
                JOIN app_account a ON a.id = rt.account_id
                WHERE rt.token_hash = ?
                  AND rt.revoked_at IS NULL
                  AND rt.expires_at > ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new ActiveRefreshToken(
                        resultSet.getObject("id", UUID.class),
                        new Account(
                                resultSet.getObject("account_id", UUID.class),
                                resultSet.getString("username"),
                                resultSet.getString("password_hash"),
                                resultSet.getBoolean("enabled"))),
                tokenHash,
                Timestamp.from(now));
        return tokens.stream().findFirst();
    }

    public void rotate(UUID tokenId, UUID replacementId, Instant revokedAt) {
        jdbcTemplate.update("""
                UPDATE refresh_token
                SET revoked_at = ?, replaced_by_token_id = ?
                WHERE id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), replacementId, tokenId);
    }

    public void revoke(String tokenHash, Instant revokedAt) {
        jdbcTemplate.update("""
                UPDATE refresh_token
                SET revoked_at = ?
                WHERE token_hash = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), tokenHash);
    }

    public record ActiveRefreshToken(UUID id, Account account) {
    }
}
