package com.azylivraison.pricing.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.pricing.domain.PricingCenter;
import com.azylivraison.pricing.domain.PricingPolicy;
import com.azylivraison.pricing.domain.PricingZone;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

@Repository
public class PricingPolicyRepository {

    private final JdbcTemplate jdbcTemplate;

    public PricingPolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PricingPolicy> find() {
        ResultSetExtractor<Optional<PricingPolicy>> extractor = this::assemblePolicy;
        return jdbcTemplate.query("""
                SELECT policy.center_label, policy.center_query,
                       policy.center_latitude, policy.center_longitude,
                       policy.version, policy.created_at, policy.updated_at,
                       zone.id AS zone_id, zone.name AS zone_name,
                       zone.max_distance_meters, zone.delivery_fee_ariary
                FROM delivery_pricing_policy policy
                JOIN delivery_pricing_zone zone ON zone.pricing_policy_id = policy.id
                WHERE policy.id = 1
                ORDER BY zone.zone_order
                """, extractor);
    }

    public boolean updateCenter(PricingCenter center, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE delivery_pricing_policy
                SET center_label = ?, center_query = ?, center_latitude = ?, center_longitude = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = 1 AND version = ?
                """,
                center.label(), center.query(), center.latitude(), center.longitude(), expectedVersion) == 1;
    }

    public void replaceZones(List<PricingZone> zones) {
        jdbcTemplate.update("DELETE FROM delivery_pricing_zone WHERE pricing_policy_id = 1");
        jdbcTemplate.batchUpdate("""
                INSERT INTO delivery_pricing_zone (
                    id, pricing_policy_id, zone_order, name,
                    max_distance_meters, delivery_fee_ariary
                ) VALUES (?, 1, ?, ?, ?, ?)
                """, zones, zones.size(), (statement, zone) -> {
            statement.setObject(1, zone.id());
            statement.setInt(2, zones.indexOf(zone) + 1);
            statement.setString(3, zone.name());
            statement.setLong(4, zone.maxDistanceMeters());
            statement.setLong(5, zone.deliveryFeeAriary());
        });
    }

    private Optional<PricingPolicy> assemblePolicy(ResultSet resultSet) throws SQLException {
        PricingCenter center = null;
        long version = 0;
        Instant createdAt = null;
        Instant updatedAt = null;
        java.util.ArrayList<PricingZone> zones = new java.util.ArrayList<>();
        while (resultSet.next()) {
            if (center == null) {
                center = new PricingCenter(
                        resultSet.getString("center_label"),
                        resultSet.getString("center_query"),
                        resultSet.getDouble("center_latitude"),
                        resultSet.getDouble("center_longitude"));
                version = resultSet.getLong("version");
                createdAt = resultSet.getTimestamp("created_at").toInstant();
                updatedAt = resultSet.getTimestamp("updated_at").toInstant();
            }
            zones.add(new PricingZone(
                    resultSet.getObject("zone_id", UUID.class),
                    resultSet.getString("zone_name"),
                    resultSet.getLong("max_distance_meters"),
                    resultSet.getLong("delivery_fee_ariary")));
        }
        return center == null
                ? Optional.empty()
                : Optional.of(new PricingPolicy(center, zones, version, createdAt, updatedAt));
    }
}
