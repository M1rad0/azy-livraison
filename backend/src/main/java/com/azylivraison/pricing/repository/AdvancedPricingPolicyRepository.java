package com.azylivraison.pricing.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.pricing.domain.AdvancedPricingPolicy;
import com.azylivraison.pricing.domain.PricingCenter;
import com.azylivraison.pricing.domain.PricingDistanceMode;
import com.azylivraison.pricing.domain.PricingTimeSlot;
import com.azylivraison.pricing.domain.PricingWeightTier;
import com.azylivraison.pricing.domain.PricingZone;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdvancedPricingPolicyRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdvancedPricingPolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AdvancedPricingPolicy> findEffective(UUID clientAccountId, DeliveryRequestKind kind) {
        List<Short> ids = jdbcTemplate.query("""
                SELECT id FROM delivery_pricing_policy
                WHERE active = TRUE AND request_kind = ?
                  AND (client_account_id = ? OR client_account_id IS NULL)
                ORDER BY CASE WHEN client_account_id = ? THEN 0 ELSE 1 END
                LIMIT 1
                """, (rs, row) -> rs.getShort(1), kind.name(), clientAccountId, clientAccountId);
        return ids.stream().findFirst().flatMap(this::findById);
    }

    public Optional<AdvancedPricingPolicy> findDefault(DeliveryRequestKind kind) {
        return findExact(null, kind);
    }

    public Optional<AdvancedPricingPolicy> findExact(UUID clientAccountId, DeliveryRequestKind kind) {
        List<Short> ids = clientAccountId == null
                ? jdbcTemplate.query("""
                    SELECT id FROM delivery_pricing_policy
                    WHERE active = TRUE AND client_account_id IS NULL AND request_kind = ?
                    """, (rs, row) -> rs.getShort(1), kind.name())
                : jdbcTemplate.query("""
                    SELECT id FROM delivery_pricing_policy
                    WHERE active = TRUE AND client_account_id = ? AND request_kind = ?
                    """, (rs, row) -> rs.getShort(1), clientAccountId, kind.name());
        return ids.stream().findFirst().flatMap(this::findById);
    }

    public List<AdvancedPricingPolicy> findForClientWithDefaults(UUID clientAccountId) {
        return List.of(
                findEffective(clientAccountId, DeliveryRequestKind.NORMAL).orElseThrow(),
                findEffective(clientAccountId, DeliveryRequestKind.EXPRESS).orElseThrow());
    }

    public boolean update(AdvancedPricingPolicy policy, long expectedVersion) {
        int updated = jdbcTemplate.update("""
                UPDATE delivery_pricing_policy
                SET center_label = ?, center_query = ?, center_latitude = ?, center_longitude = ?,
                    distance_mode = ?, default_weight_kg = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND version = ? AND active = TRUE
                """, policy.center().label(), policy.center().query(), policy.center().latitude(),
                policy.center().longitude(), policy.distanceMode().name(), policy.defaultWeightKg(),
                policy.id(), expectedVersion);
        if (updated == 0) return false;
        replaceChildren(policy);
        return true;
    }

    public short create(AdvancedPricingPolicy policy) {
        jdbcTemplate.queryForObject("SELECT id FROM delivery_pricing_policy WHERE id = 1 FOR UPDATE",
                Short.class);
        Integer nextId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM delivery_pricing_policy", Integer.class);
        if (nextId == null || nextId > Short.MAX_VALUE) {
            throw new IllegalStateException("Le nombre maximal de grilles tarifaires est atteint.");
        }
        short id = nextId.shortValue();
        jdbcTemplate.update("""
                INSERT INTO delivery_pricing_policy (
                    id, center_label, center_query, center_latitude, center_longitude,
                    client_account_id, request_kind, distance_mode, default_weight_kg, active, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, 0)
                """, id, policy.center().label(), policy.center().query(), policy.center().latitude(),
                policy.center().longitude(), policy.clientAccountId(), policy.requestKind().name(),
                policy.distanceMode().name(), policy.defaultWeightKg());
        AdvancedPricingPolicy stored = new AdvancedPricingPolicy(id, policy.clientAccountId(),
                policy.requestKind(), policy.distanceMode(), policy.center(), policy.defaultWeightKg(), policy.zones(),
                policy.weightTiers(), policy.timeSlots(), 0, policy.createdAt(), policy.updatedAt());
        replaceChildren(stored);
        return id;
    }

    public void deactivate(UUID clientAccountId, DeliveryRequestKind kind) {
        jdbcTemplate.update("""
                UPDATE delivery_pricing_policy
                SET active = FALSE, updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE client_account_id = ? AND request_kind = ? AND active = TRUE
                """, clientAccountId, kind.name());
    }

    private Optional<AdvancedPricingPolicy> findById(short id) {
        List<AdvancedPricingPolicy> policies = jdbcTemplate.query("""
                SELECT * FROM delivery_pricing_policy WHERE id = ? AND active = TRUE
                """, this::mapPolicy, id);
        return policies.stream().findFirst();
    }

    private AdvancedPricingPolicy mapPolicy(ResultSet rs, int row) throws SQLException {
        short id = rs.getShort("id");
        return new AdvancedPricingPolicy(
                id, rs.getObject("client_account_id", UUID.class),
                DeliveryRequestKind.valueOf(rs.getString("request_kind")),
                PricingDistanceMode.valueOf(rs.getString("distance_mode")),
                new PricingCenter(rs.getString("center_label"), rs.getString("center_query"),
                        rs.getDouble("center_latitude"), rs.getDouble("center_longitude")),
                rs.getBigDecimal("default_weight_kg"), findZones(id), findWeights(id), findSlots(id),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private List<PricingZone> findZones(short id) {
        return jdbcTemplate.query("""
                SELECT id, name, max_distance_meters, delivery_fee_ariary
                FROM delivery_pricing_zone WHERE pricing_policy_id = ? ORDER BY zone_order
                """, (rs, row) -> new PricingZone(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getLong("max_distance_meters"), rs.getLong("delivery_fee_ariary")), id);
    }

    private List<PricingWeightTier> findWeights(short id) {
        return jdbcTemplate.query("""
                SELECT id, max_weight_kg, surcharge_ariary
                FROM delivery_pricing_weight_tier WHERE pricing_policy_id = ? ORDER BY tier_order
                """, (rs, row) -> new PricingWeightTier(rs.getObject("id", UUID.class),
                        rs.getBigDecimal("max_weight_kg"), rs.getLong("surcharge_ariary")), id);
    }

    private List<PricingTimeSlot> findSlots(short id) {
        return jdbcTemplate.query("""
                SELECT id, label, start_time, end_time, surcharge_ariary
                FROM delivery_pricing_time_slot WHERE pricing_policy_id = ? ORDER BY slot_order
                """, (rs, row) -> new PricingTimeSlot(rs.getObject("id", UUID.class), rs.getString("label"),
                        rs.getObject("start_time", java.time.LocalTime.class),
                        rs.getObject("end_time", java.time.LocalTime.class), rs.getLong("surcharge_ariary")), id);
    }

    private void replaceChildren(AdvancedPricingPolicy policy) {
        jdbcTemplate.update("DELETE FROM delivery_pricing_zone WHERE pricing_policy_id = ?", policy.id());
        jdbcTemplate.update("DELETE FROM delivery_pricing_weight_tier WHERE pricing_policy_id = ?", policy.id());
        jdbcTemplate.update("DELETE FROM delivery_pricing_time_slot WHERE pricing_policy_id = ?", policy.id());
        for (int index = 0; index < policy.zones().size(); index++) {
            PricingZone zone = policy.zones().get(index);
            jdbcTemplate.update("""
                    INSERT INTO delivery_pricing_zone
                        (id, pricing_policy_id, zone_order, name, max_distance_meters, delivery_fee_ariary)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, zone.id(), policy.id(), index + 1, zone.name(), zone.maxDistanceMeters(),
                    zone.deliveryFeeAriary());
        }
        for (int index = 0; index < policy.weightTiers().size(); index++) {
            PricingWeightTier tier = policy.weightTiers().get(index);
            jdbcTemplate.update("""
                    INSERT INTO delivery_pricing_weight_tier
                        (id, pricing_policy_id, tier_order, max_weight_kg, surcharge_ariary)
                    VALUES (?, ?, ?, ?, ?)
                    """, tier.id(), policy.id(), index + 1, tier.maxWeightKg(), tier.surchargeAriary());
        }
        for (int index = 0; index < policy.timeSlots().size(); index++) {
            PricingTimeSlot slot = policy.timeSlots().get(index);
            jdbcTemplate.update("""
                    INSERT INTO delivery_pricing_time_slot
                        (id, pricing_policy_id, slot_order, label, start_time, end_time, surcharge_ariary)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, slot.id(), policy.id(), index + 1, slot.label(), slot.startTime(), slot.endTime(),
                    slot.surchargeAriary());
        }
    }
}
