package com.azylivraison.geography.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GeocodingCacheRepository {

    private final JdbcTemplate jdbcTemplate;

    public GeocodingCacheRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CachedGeocoding> find(String normalizedQuery, String countryCode, Instant now) {
        List<CachedGeocoding> entries = jdbcTemplate.query("""
                SELECT provider, response_json, created_at
                FROM geocoding_cache
                WHERE normalized_query = ?
                  AND country_code = ?
                  AND expires_at > ?
                """, (resultSet, rowNumber) -> new CachedGeocoding(
                        resultSet.getString("provider"),
                        resultSet.getString("response_json"),
                        resultSet.getTimestamp("created_at").toInstant()),
                normalizedQuery,
                countryCode,
                Timestamp.from(now));
        return entries.stream().findFirst();
    }

    public void save(
            String normalizedQuery,
            String countryCode,
            String provider,
            String responseJson,
            Instant createdAt,
            Instant expiresAt) {
        int updated = update(normalizedQuery, countryCode, provider, responseJson, createdAt, expiresAt);
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO geocoding_cache (
                        normalized_query, country_code, provider, response_json, created_at, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    normalizedQuery,
                    countryCode,
                    provider,
                    responseJson,
                    Timestamp.from(createdAt),
                    Timestamp.from(expiresAt));
        } catch (DuplicateKeyException exception) {
            update(normalizedQuery, countryCode, provider, responseJson, createdAt, expiresAt);
        }
    }

    private int update(
            String normalizedQuery,
            String countryCode,
            String provider,
            String responseJson,
            Instant createdAt,
            Instant expiresAt) {
        return jdbcTemplate.update("""
                UPDATE geocoding_cache
                SET provider = ?, response_json = ?, created_at = ?, expires_at = ?
                WHERE normalized_query = ? AND country_code = ?
                """,
                provider,
                responseJson,
                Timestamp.from(createdAt),
                Timestamp.from(expiresAt),
                normalizedQuery,
                countryCode);
    }

    public record CachedGeocoding(String provider, String responseJson, Instant createdAt) {
    }
}
