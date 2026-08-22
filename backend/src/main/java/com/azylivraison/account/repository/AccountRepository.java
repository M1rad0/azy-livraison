package com.azylivraison.account.repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.account.domain.Account;
import com.azylivraison.account.domain.AccountRole;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Account> findByUsername(String username) {
        List<Account> accounts = jdbcTemplate.query("""
                SELECT id, username, password_hash, enabled, role, display_name, phone
                FROM app_account
                WHERE username = ?
                """, (resultSet, rowNumber) -> new Account(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("username"),
                        resultSet.getString("password_hash"),
                        resultSet.getBoolean("enabled"),
                        AccountRole.valueOf(resultSet.getString("role")),
                        resultSet.getString("display_name"),
                        resultSet.getString("phone")),
                normalizeUsername(username));
        return accounts.stream().findFirst();
    }

    public Optional<Account> findById(UUID id) {
        List<Account> accounts = jdbcTemplate.query("""
                SELECT id, username, password_hash, enabled, role, display_name, phone
                FROM app_account
                WHERE id = ?
                """, (resultSet, rowNumber) -> new Account(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("username"),
                        resultSet.getString("password_hash"),
                        resultSet.getBoolean("enabled"),
                        AccountRole.valueOf(resultSet.getString("role")),
                        resultSet.getString("display_name"),
                        resultSet.getString("phone")),
                id);
        return accounts.stream().findFirst();
    }

    public void create(Account account) {
        jdbcTemplate.update("""
                INSERT INTO app_account (id, username, password_hash, enabled, role, display_name, phone)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                account.id(),
                normalizeUsername(account.username()),
                account.passwordHash(),
                account.enabled(),
                account.role().name(),
                account.displayName(),
                account.phone());
    }

    public void updateProfile(UUID accountId, String displayName, String phone) {
        jdbcTemplate.update("""
                UPDATE app_account
                SET display_name = ?, phone = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, displayName, phone, accountId);
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
