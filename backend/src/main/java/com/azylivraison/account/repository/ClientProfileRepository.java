package com.azylivraison.account.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.account.domain.ClientProfile;
import com.azylivraison.account.domain.DefaultPickupLocation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ClientProfileRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClientProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(UUID accountId) {
        jdbcTemplate.update("""
                INSERT INTO client_profile (account_id)
                VALUES (?)
                """, accountId);
    }

    public Optional<ClientProfile> findByAccountId(UUID accountId) {
        List<ClientProfile> profiles = jdbcTemplate.query("""
                SELECT account_id, default_pickup_label, default_pickup_query,
                       default_pickup_latitude, default_pickup_longitude, version
                FROM client_profile
                WHERE account_id = ?
                """, (resultSet, rowNumber) -> {
                    String label = resultSet.getString("default_pickup_label");
                    String query = resultSet.getString("default_pickup_query");
                    Double latitude = resultSet.getObject("default_pickup_latitude", Double.class);
                    Double longitude = resultSet.getObject("default_pickup_longitude", Double.class);
                    DefaultPickupLocation defaultPickup = label == null
                            && query == null
                            && latitude == null
                            && longitude == null
                                    ? null
                                    : new DefaultPickupLocation(label, query, latitude, longitude);
                    return new ClientProfile(
                            resultSet.getObject("account_id", UUID.class),
                            defaultPickup,
                            resultSet.getLong("version"));
                }, accountId);
        return profiles.stream().findFirst();
    }

    public void updateDefaultPickup(UUID accountId, DefaultPickupLocation pickup) {
        jdbcTemplate.update("""
                UPDATE client_profile
                SET default_pickup_label = ?,
                    default_pickup_query = ?,
                    default_pickup_latitude = ?,
                    default_pickup_longitude = ?,
                    updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE account_id = ?
                """,
                pickup == null ? null : pickup.label(),
                pickup == null ? null : pickup.query(),
                pickup == null ? null : pickup.latitude(),
                pickup == null ? null : pickup.longitude(),
                accountId);
    }
}
