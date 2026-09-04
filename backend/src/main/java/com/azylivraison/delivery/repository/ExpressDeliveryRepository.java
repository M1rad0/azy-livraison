package com.azylivraison.delivery.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExpressDeliveryRepository {

    private final JdbcTemplate jdbcTemplate;

    public ExpressDeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<UUID> findActiveIds() {
        return jdbcTemplate.query("""
                SELECT id FROM delivery_request
                WHERE request_kind = 'EXPRESS' AND cancelled_before_processing_at IS NULL
                ORDER BY CASE express_status WHEN 'PENDING' THEN 0 WHEN 'CONFIRMED' THEN 1 ELSE 2 END,
                         created_at DESC
                """, (rs, row) -> rs.getObject(1, UUID.class));
    }

    public ExpressState lock(UUID requestId) {
        List<ExpressState> states = jdbcTemplate.query("""
                SELECT id, owner_account_id, express_status, version,
                       cancelled_before_processing_at,
                       (SELECT COALESCE(SUM(effective_weight_kg), 0)
                        FROM delivery_package WHERE delivery_request_id = delivery_request.id
                          AND cancelled_before_processing_at IS NULL) AS total_weight
                FROM delivery_request
                WHERE id = ? AND request_kind = 'EXPRESS'
                FOR UPDATE
                """, (rs, row) -> new ExpressState(rs.getObject("id", UUID.class),
                        rs.getObject("owner_account_id", UUID.class), rs.getString("express_status"),
                        rs.getLong("version"), rs.getTimestamp("cancelled_before_processing_at") != null,
                        rs.getBigDecimal("total_weight")), requestId);
        return states.stream().findFirst().orElse(null);
    }

    public boolean confirm(UUID requestId, long expectedVersion, List<CourierSnapshot> couriers,
            UUID actorId, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE delivery_request
                SET express_status = 'CONFIRMED', status = 'DISPATCHED',
                    version = version + 1, updated_at = ?
                WHERE id = ? AND request_kind = 'EXPRESS' AND express_status = 'PENDING'
                  AND cancelled_before_processing_at IS NULL AND version = ?
                """, Timestamp.from(now), requestId, expectedVersion);
        if (updated == 0) return false;
        jdbcTemplate.update("""
                UPDATE delivery_package SET status = 'DISPATCHED', version = version + 1, updated_at = ?
                WHERE delivery_request_id = ? AND cancelled_before_processing_at IS NULL
                """, Timestamp.from(now), requestId);
        for (CourierSnapshot courier : couriers) {
            jdbcTemplate.update("""
                    INSERT INTO express_delivery_courier (
                        delivery_request_id, courier_id, courier_name_snapshot,
                        selected_at, selected_by_account_id
                    ) VALUES (?, ?, ?, ?, ?)
                    """, requestId, courier.id(), courier.name(), Timestamp.from(now), actorId);
        }
        event(requestId, "EXPRESS_CONFIRMED", "PENDING", "CONFIRMED", actorId, now);
        return true;
    }

    public boolean cancelPending(UUID requestId, long expectedVersion, UUID actorId, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE delivery_request
                SET cancelled_before_processing_at = ?,
                    cancelled_before_processing_by_account_id = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND request_kind = 'EXPRESS' AND express_status = 'PENDING'
                  AND cancelled_before_processing_at IS NULL AND version = ?
                """, Timestamp.from(now), actorId, Timestamp.from(now), requestId, expectedVersion);
        if (updated == 0) return false;
        jdbcTemplate.update("""
                UPDATE delivery_package
                SET cancelled_before_processing_at = ?, cancelled_before_processing_by_account_id = ?,
                    version = version + 1, updated_at = ?
                WHERE delivery_request_id = ?
                """, Timestamp.from(now), actorId, Timestamp.from(now), requestId);
        event(requestId, "EXPRESS_CANCELLED_BEFORE_PROCESSING", "PENDING", "CANCELLED", actorId, now);
        return true;
    }

    public List<CourierSnapshot> couriers(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT courier_id, courier_name_snapshot
                FROM express_delivery_courier WHERE delivery_request_id = ? ORDER BY selected_at, courier_id
                """, (rs, row) -> new CourierSnapshot(rs.getObject("courier_id", UUID.class),
                        rs.getString("courier_name_snapshot"), BigDecimal.ZERO), requestId);
    }

    public Instant confirmedAt(UUID requestId) {
        List<Instant> values = jdbcTemplate.query("""
                SELECT MIN(selected_at) FROM express_delivery_courier WHERE delivery_request_id = ?
                """, (rs, row) -> {
                    Timestamp value = rs.getTimestamp(1);
                    return value == null ? null : value.toInstant();
                }, requestId);
        return values.isEmpty() ? null : values.getFirst();
    }

    public void completeIfFinal(UUID requestId) {
        jdbcTemplate.update("""
                UPDATE delivery_request request
                SET express_status = 'COMPLETED', version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE request.id = ? AND request.request_kind = 'EXPRESS'
                  AND request.express_status = 'CONFIRMED'
                  AND NOT EXISTS (
                      SELECT 1 FROM delivery_package package
                      WHERE package.delivery_request_id = request.id
                        AND package.cancelled_before_processing_at IS NULL
                        AND package.final_outcome IS NULL
                  )
                """, requestId);
    }

    private void event(UUID requestId, String type, String from, String to, UUID actorId, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO delivery_event
                    (id, delivery_request_id, event_type, from_status, to_status, actor_account_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), requestId, type, from, to, actorId, Timestamp.from(now));
    }

    public record ExpressState(UUID id, UUID ownerAccountId, String status, long version,
            boolean cancelled, BigDecimal totalWeightKg) {}
    public record CourierSnapshot(UUID id, String name, BigDecimal capacityKg) {}
}
