package com.azylivraison.delivery.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
                SELECT id, owner_account_id, requested_date, express_status, version,
                       cancelled_before_processing_at,
                       (SELECT COALESCE(SUM(effective_weight_kg), 0)
                        FROM delivery_package WHERE delivery_request_id = delivery_request.id
                          AND cancelled_before_processing_at IS NULL) AS total_weight
                FROM delivery_request
                WHERE id = ? AND request_kind = 'EXPRESS'
                FOR UPDATE
                """, (rs, row) -> new ExpressState(rs.getObject("id", UUID.class),
                        rs.getObject("owner_account_id", UUID.class),
                        rs.getObject("requested_date", LocalDate.class), rs.getString("express_status"),
                        rs.getLong("version"), rs.getTimestamp("cancelled_before_processing_at") != null,
                        rs.getBigDecimal("total_weight")), requestId);
        return states.stream().findFirst().orElse(null);
    }

    /**
     * Creates the operational day needed to follow an express request without
     * reserving it for a normal dispatch or closing admissions. The admission
     * gate is shared with dispatch creation to make the insert/status check
     * atomic.
     */
    public OperationDayState ensureOperationDay(LocalDate serviceDate, UUID actorId, Instant now) {
        jdbcTemplate.queryForObject(
                "SELECT id FROM operational_settings WHERE id = 1 FOR UPDATE", Integer.class);
        List<OperationDayState> days = jdbcTemplate.query("""
                SELECT status, dispatch_plan_id
                FROM delivery_operation_day
                WHERE service_date = ?
                FOR UPDATE
                """, (rs, row) -> new OperationDayState(
                rs.getString("status"), rs.getObject("dispatch_plan_id", UUID.class)),
                Date.valueOf(serviceDate));
        if (!days.isEmpty()) return days.getFirst();

        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day (
                    service_date, status, admissions_closed_at,
                    dispatch_plan_id, created_at, updated_at
                ) VALUES (?, 'OPEN', NULL, NULL, ?, ?)
                """, Date.valueOf(serviceDate), Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day_event (
                    id, service_date, event_type, actor_account_id, payload_json, created_at
                ) VALUES (?, ?, 'EXPRESS_ACTIVITY_OPENED', ?, NULL, ?)
                """, UUID.randomUUID(), Date.valueOf(serviceDate), actorId, Timestamp.from(now));
        return new OperationDayState("OPEN", null);
    }

    public boolean confirm(UUID requestId, long expectedVersion, List<CourierSnapshot> couriers,
            boolean returnToPickup, boolean returnToPickupOverridden, UUID actorId, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE delivery_request
                SET express_status = 'CONFIRMED', status = 'DISPATCHED',
                    express_return_to_pickup = ?, express_return_to_pickup_overridden = ?,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND request_kind = 'EXPRESS' AND express_status = 'PENDING'
                  AND cancelled_before_processing_at IS NULL AND version = ?
                """, returnToPickup, returnToPickupOverridden, Timestamp.from(now), requestId, expectedVersion);
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

    public ReturnConfiguration returnConfiguration(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT express_return_to_pickup, express_return_to_pickup_overridden
                FROM delivery_request WHERE id = ? AND request_kind = 'EXPRESS'
                """, (rs, row) -> new ReturnConfiguration(
                rs.getBoolean("express_return_to_pickup"),
                rs.getBoolean("express_return_to_pickup_overridden")), requestId)
                .stream().findFirst().orElse(null);
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

    public record ExpressState(UUID id, UUID ownerAccountId, LocalDate requestedDate, String status, long version,
            boolean cancelled, BigDecimal totalWeightKg) {}
    public record CourierSnapshot(UUID id, String name, BigDecimal capacityKg) {}
    public record ReturnConfiguration(boolean returnToPickup, boolean overridden) {}
    public record OperationDayState(String status, UUID dispatchPlanId) {}
}
