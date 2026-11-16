package com.azylivraison.account.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.account.domain.ClientProfile;
import com.azylivraison.account.domain.ClientContactNumber;
import com.azylivraison.account.domain.ClientPayoutDestination;
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

    public void createPrimaryContact(UUID accountId, String number) {
        jdbcTemplate.update("""
                INSERT INTO client_contact_number (
                    id, account_id, label, number, priority, active
                ) VALUES (?, ?, 'Principal', ?, 1, TRUE)
                """, UUID.randomUUID(), accountId, number);
    }

    public Optional<ClientProfile> findByAccountId(UUID accountId) {
        List<ClientProfile> profiles = jdbcTemplate.query("""
                SELECT account_id, default_pickup_label, default_pickup_query,
                       default_pickup_latitude, default_pickup_longitude,
                       express_delivery_enabled, version
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
                            resultSet.getBoolean("express_delivery_enabled"),
                            findContactNumbers(accountId),
                            findPayoutDestinations(accountId),
                            resultSet.getLong("version"));
                }, accountId);
        return profiles.stream().findFirst();
    }

    public boolean updateExpressDeliveryPermission(UUID accountId, boolean enabled, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE client_profile
                SET express_delivery_enabled = ?, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE account_id = ? AND version = ?
                """, enabled, accountId, expectedVersion) == 1;
    }

    public boolean bumpVersion(UUID accountId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE client_profile
                SET version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE account_id = ? AND version = ?
                """, accountId, expectedVersion) == 1;
    }

    public boolean updateAdminPreferences(UUID accountId, boolean expressEnabled,
            DefaultPickupLocation pickup, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE client_profile
                SET express_delivery_enabled = ?, default_pickup_label = ?, default_pickup_query = ?,
                    default_pickup_latitude = ?, default_pickup_longitude = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE account_id = ? AND version = ?
                """, expressEnabled, pickup == null ? null : pickup.label(),
                pickup == null ? null : pickup.query(), pickup == null ? null : pickup.latitude(),
                pickup == null ? null : pickup.longitude(), accountId, expectedVersion) == 1;
    }

    public void replaceContactNumbers(UUID accountId, List<ClientContactNumber> contacts) {
        jdbcTemplate.update("DELETE FROM client_contact_number WHERE account_id = ?", accountId);
        for (ClientContactNumber contact : contacts) {
            jdbcTemplate.update("""
                    INSERT INTO client_contact_number (
                        id, account_id, label, number, priority, active
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, contact.id(), accountId, contact.label(), contact.number(),
                    contact.priority(), contact.active());
        }
    }

    public void updatePrimaryContact(UUID accountId, String number) {
        int updated = jdbcTemplate.update("""
                UPDATE client_contact_number
                SET number = ?, active = TRUE, updated_at = CURRENT_TIMESTAMP
                WHERE account_id = ? AND priority = 1
                """, number, accountId);
        if (updated == 0) {
            createPrimaryContact(accountId, number);
        }
    }

    public void replacePayoutDestinations(UUID accountId, List<ClientPayoutDestination> destinations) {
        jdbcTemplate.update("DELETE FROM client_payout_destination WHERE account_id = ?", accountId);
        for (ClientPayoutDestination destination : destinations) {
            jdbcTemplate.update("""
                    INSERT INTO client_payout_destination (
                        id, account_id, operator, account_holder, number, priority, active
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, destination.id(), accountId, destination.operator(),
                    destination.accountHolder(), destination.number(), destination.priority(),
                    destination.active());
        }
    }

    private List<ClientContactNumber> findContactNumbers(UUID accountId) {
        return jdbcTemplate.query("""
                SELECT id, label, number, priority, active
                FROM client_contact_number
                WHERE account_id = ?
                ORDER BY priority, id
                """, (resultSet, rowNumber) -> new ClientContactNumber(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("label"),
                        resultSet.getString("number"),
                        resultSet.getInt("priority"),
                        resultSet.getBoolean("active")), accountId);
    }

    private List<ClientPayoutDestination> findPayoutDestinations(UUID accountId) {
        return jdbcTemplate.query("""
                SELECT id, operator, account_holder, number, priority, active
                FROM client_payout_destination
                WHERE account_id = ?
                ORDER BY priority, id
                """, (resultSet, rowNumber) -> new ClientPayoutDestination(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("operator"),
                        resultSet.getString("account_holder"),
                        resultSet.getString("number"),
                        resultSet.getInt("priority"),
                        resultSet.getBoolean("active")), accountId);
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
