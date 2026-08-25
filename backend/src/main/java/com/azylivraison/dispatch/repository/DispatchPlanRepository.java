package com.azylivraison.dispatch.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.dispatch.domain.DispatchCourierResult;
import com.azylivraison.dispatch.domain.DispatchPackageOutcome;
import com.azylivraison.dispatch.domain.DispatchPlanRecord;
import com.azylivraison.dispatch.domain.DispatchStatus;
import com.azylivraison.dispatch.domain.DispatchStop;
import com.azylivraison.dispatch.domain.SelectedCourierSnapshot;
import com.azylivraison.optimization.domain.ViaHubOptimizationResult;
import com.azylivraison.planning.domain.Location;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DispatchPlanRepository {

    private static final String SELECT_PLAN = """
            SELECT id, service_date, status, created_by_account_id,
                   idempotency_key, request_hash,
                   hub_label, hub_query, hub_latitude, hub_longitude,
                   input_snapshot_json, policy_snapshot_json,
                   result_json, hub_execution_json, metrics_json,
                   result_status, solver_status,
                   selected_package_count, assigned_package_count, unassigned_package_count,
                   selected_courier_count, used_courier_count,
                   total_distance_meters, total_duration_seconds,
                   meeting_at, redistribution_completed_at, completed_at,
                   failure_code, failure_message,
                   created_at, completed_processing_at
            FROM dispatch_plan
            """;

    private final JdbcTemplate jdbcTemplate;

    public DispatchPlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DispatchPlanRecord> findByActorAndIdempotencyKey(UUID actorId, String key) {
        return jdbcTemplate.query(
                        SELECT_PLAN + " WHERE created_by_account_id = ? AND idempotency_key = ?",
                        (resultSet, rowNumber) -> mapPlan(resultSet),
                        actorId,
                        key)
                .stream()
                .findFirst();
    }

    public Optional<DispatchPlanRecord> findById(UUID id) {
        return jdbcTemplate.query(
                        SELECT_PLAN + " WHERE id = ?",
                        (resultSet, rowNumber) -> mapPlan(resultSet),
                        id)
                .stream()
                .findFirst();
    }

    public List<DispatchPlanRecord> findAll(LocalDate serviceDate) {
        if (serviceDate == null) {
            return jdbcTemplate.query(
                    SELECT_PLAN + " ORDER BY service_date DESC, created_at DESC",
                    (resultSet, rowNumber) -> mapPlan(resultSet));
        }
        return jdbcTemplate.query(
                SELECT_PLAN + " WHERE service_date = ? ORDER BY created_at DESC",
                (resultSet, rowNumber) -> mapPlan(resultSet),
                serviceDate);
    }

    public void createGenerating(
            UUID id,
            LocalDate serviceDate,
            UUID actorId,
            String idempotencyKey,
            String requestHash,
            Location hub,
            String inputSnapshotJson,
            String policySnapshotJson,
            int packageCount,
            int courierCount) {
        jdbcTemplate.update("""
                INSERT INTO dispatch_plan (
                    id, service_date, status, strategy, created_by_account_id,
                    idempotency_key, request_hash,
                    hub_label, hub_query, hub_latitude, hub_longitude,
                    input_snapshot_json, policy_snapshot_json,
                    selected_package_count, selected_courier_count
                ) VALUES (?, ?, 'GENERATING', 'VIA_HUB', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                serviceDate,
                actorId,
                idempotencyKey,
                requestHash,
                hub.label(),
                hub.destinationQuery(),
                hub.latitude(),
                hub.longitude(),
                inputSnapshotJson,
                policySnapshotJson,
                packageCount,
                courierCount);
    }

    public void reserveDay(UUID dispatchPlanId, LocalDate serviceDate) {
        jdbcTemplate.update("""
                INSERT INTO dispatch_day_reservation (service_date, dispatch_plan_id)
                VALUES (?, ?)
                """, serviceDate, dispatchPlanId);
    }

    public void reservePackages(UUID dispatchPlanId, List<UUID> packageIds) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO dispatch_package_reservation (delivery_package_id, dispatch_plan_id) VALUES (?, ?)",
                packageIds,
                packageIds.size(),
                (statement, packageId) -> {
                    statement.setObject(1, packageId);
                    statement.setObject(2, dispatchPlanId);
                });
    }

    public void saveSelectedCouriers(UUID dispatchPlanId, List<SelectedCourierSnapshot> couriers) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO dispatch_plan_courier (
                    dispatch_plan_id, courier_id, courier_name, courier_contact,
                    vehicle_type, max_weight_kg, average_speed_kmh,
                    departure_at, departure_label, departure_query,
                    departure_latitude, departure_longitude
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, couriers, couriers.size(), (statement, selected) -> {
            var courier = selected.courier();
            statement.setObject(1, dispatchPlanId);
            statement.setObject(2, selected.profileId());
            statement.setString(3, courier.name());
            statement.setString(4, selected.contact());
            statement.setString(5, courier.vehicleType().name());
            statement.setBigDecimal(6, courier.maxWeightKg());
            statement.setBigDecimal(7, courier.averageSpeedKmh());
            statement.setTimestamp(8, Timestamp.from(courier.departureAt().toInstant()));
            statement.setString(9, courier.departureLocation().label());
            statement.setString(10, courier.departureLocation().destinationQuery());
            statement.setDouble(11, courier.departureLocation().latitude());
            statement.setDouble(12, courier.departureLocation().longitude());
        });
    }

    public void markReady(
            UUID dispatchPlanId,
            DispatchStatus status,
            ViaHubOptimizationResult optimization,
            String resultJson,
            String hubExecutionJson,
            String metricsJson) {
        var result = optimization.result();
        var metrics = optimization.metrics();
        var hub = optimization.hubExecution();
        jdbcTemplate.update("""
                UPDATE dispatch_plan
                SET status = ?,
                    result_json = ?,
                    hub_execution_json = ?,
                    metrics_json = ?,
                    result_status = ?,
                    solver_status = ?,
                    assigned_package_count = ?,
                    unassigned_package_count = ?,
                    used_courier_count = ?,
                    total_distance_meters = ?,
                    total_duration_seconds = ?,
                    meeting_at = ?,
                    redistribution_completed_at = ?,
                    completed_at = ?,
                    completed_processing_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'GENERATING'
                """,
                status.name(),
                resultJson,
                hubExecutionJson,
                metricsJson,
                result.status().name(),
                result.solverStatus(),
                metrics.assignedPackageCount(),
                metrics.packageCount() - metrics.assignedPackageCount(),
                metrics.usedCourierCount(),
                metrics.totalDistanceMeters(),
                Math.round(result.summary().totalDurationSeconds()),
                timestamp(hub.meetingAt()),
                timestamp(hub.redistributionCompletedAt()),
                timestamp(metrics.completedAt()),
                dispatchPlanId);
    }

    public void markFailed(UUID dispatchPlanId, String code, String message) {
        jdbcTemplate.update("""
                UPDATE dispatch_plan
                SET status = 'FAILED',
                    failure_code = ?,
                    failure_message = ?,
                    completed_processing_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'GENERATING'
                """, code, truncate(message, 1000), dispatchPlanId);
    }

    public void updateCourierResults(UUID dispatchPlanId, List<DispatchCourierResult> couriers) {
        for (DispatchCourierResult courier : couriers) {
            jdbcTemplate.update("""
                    UPDATE dispatch_plan_courier
                    SET used = ?,
                        collection_role = ?,
                        distribution_role = ?,
                        hub_arrival_at = ?,
                        hub_departure_at = ?,
                        completed_at = ?,
                        waiting_seconds = ?,
                        load_at_hub_arrival_kg = ?,
                        load_at_hub_departure_kg = ?,
                        route_distance_meters = ?,
                        route_duration_seconds = ?
                    WHERE dispatch_plan_id = ? AND courier_id = ?
                    """,
                    courier.used(),
                    courier.collectionRole(),
                    courier.distributionRole(),
                    timestamp(courier.hubArrivalAt()),
                    timestamp(courier.hubDepartureAt()),
                    timestamp(courier.completedAt()),
                    courier.waitingSeconds(),
                    courier.loadAtHubArrivalKg(),
                    courier.loadAtHubDepartureKg(),
                    courier.routeDistanceMeters(),
                    courier.routeDurationSeconds(),
                    dispatchPlanId,
                    courier.courierId());
        }
    }

    public void savePackageOutcomes(UUID dispatchPlanId, List<DispatchPackageOutcome> outcomes) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO dispatch_package_outcome (
                    dispatch_plan_id, delivery_package_id, delivery_request_id,
                    outcome, collector_courier_id, distributor_courier_id, transferred,
                    estimated_delivery_at, delivery_order,
                    unassigned_reason, unassigned_message,
                    recipient_name, faritra, contact,
                    amount_to_collect_ariary, mobile_money_paid, effective_weight_kg,
                    destination_label, destination_query,
                    destination_latitude, destination_longitude
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, outcomes, outcomes.size(), (statement, outcome) -> {
            statement.setObject(1, dispatchPlanId);
            statement.setObject(2, outcome.packageId());
            statement.setObject(3, outcome.deliveryRequestId());
            statement.setString(4, outcome.assigned() ? "ASSIGNED" : "UNASSIGNED");
            statement.setObject(5, outcome.collectorCourierId());
            statement.setObject(6, outcome.distributorCourierId());
            statement.setBoolean(7, outcome.transferred());
            statement.setTimestamp(8, timestamp(outcome.estimatedDeliveryAt()));
            if (outcome.deliveryOrder() == null) statement.setNull(9, java.sql.Types.INTEGER);
            else statement.setInt(9, outcome.deliveryOrder());
            statement.setString(10, outcome.unassignedReason());
            statement.setString(11, outcome.unassignedMessage());
            statement.setString(12, outcome.recipientName());
            statement.setBoolean(13, outcome.faritra());
            statement.setString(14, outcome.contact());
            statement.setLong(15, outcome.amountToCollectAriary());
            statement.setBoolean(16, outcome.mobileMoneyPaid());
            statement.setBigDecimal(17, outcome.effectiveWeightKg());
            statement.setString(18, outcome.destination().label());
            statement.setString(19, outcome.destination().destinationQuery());
            statement.setDouble(20, outcome.destination().latitude());
            statement.setDouble(21, outcome.destination().longitude());
        });
    }

    public void saveStops(UUID dispatchPlanId, List<DispatchStop> stops) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO dispatch_stop (
                    id, dispatch_plan_id, courier_id, stop_order, stop_type,
                    delivery_request_id, delivery_package_id, label,
                    location_label, location_query, latitude, longitude,
                    estimated_arrival_at,
                    distance_from_previous_meters, duration_from_previous_seconds,
                    cumulative_distance_meters, cumulative_duration_seconds
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stops, stops.size(), (statement, stop) -> {
            statement.setObject(1, stop.id());
            statement.setObject(2, dispatchPlanId);
            statement.setObject(3, stop.courierId());
            statement.setInt(4, stop.order());
            statement.setString(5, stop.type());
            statement.setObject(6, stop.deliveryRequestId());
            statement.setObject(7, stop.packageId());
            statement.setString(8, stop.label());
            statement.setString(9, stop.location().label());
            statement.setString(10, stop.location().destinationQuery());
            statement.setDouble(11, stop.location().latitude());
            statement.setDouble(12, stop.location().longitude());
            statement.setTimestamp(13, timestamp(stop.estimatedArrivalAt()));
            statement.setLong(14, stop.distanceFromPreviousMeters());
            statement.setLong(15, stop.durationFromPreviousSeconds());
            statement.setLong(16, stop.cumulativeDistanceMeters());
            statement.setLong(17, stop.cumulativeDurationSeconds());
        });
    }

    public List<DispatchPackageOutcome> findPackageOutcomes(UUID dispatchPlanId) {
        return jdbcTemplate.query("""
                SELECT delivery_package_id, delivery_request_id, outcome,
                       collector_courier_id, distributor_courier_id, transferred,
                       estimated_delivery_at, delivery_order,
                       unassigned_reason, unassigned_message,
                       recipient_name, faritra, contact,
                       amount_to_collect_ariary, mobile_money_paid, effective_weight_kg,
                       destination_label, destination_query,
                       destination_latitude, destination_longitude
                FROM dispatch_package_outcome
                WHERE dispatch_plan_id = ?
                ORDER BY outcome, distributor_courier_id, delivery_order, recipient_name
                """, (resultSet, rowNumber) -> new DispatchPackageOutcome(
                resultSet.getObject("delivery_package_id", UUID.class),
                resultSet.getObject("delivery_request_id", UUID.class),
                "ASSIGNED".equals(resultSet.getString("outcome")),
                resultSet.getObject("collector_courier_id", UUID.class),
                resultSet.getObject("distributor_courier_id", UUID.class),
                resultSet.getBoolean("transferred"),
                instant(resultSet, "estimated_delivery_at"),
                nullableInteger(resultSet, "delivery_order"),
                resultSet.getString("unassigned_reason"),
                resultSet.getString("unassigned_message"),
                resultSet.getString("recipient_name"),
                resultSet.getBoolean("faritra"),
                resultSet.getString("contact"),
                resultSet.getLong("amount_to_collect_ariary"),
                resultSet.getBoolean("mobile_money_paid"),
                resultSet.getBigDecimal("effective_weight_kg"),
                new Location(
                        resultSet.getString("destination_label"),
                        resultSet.getString("destination_query"),
                        resultSet.getDouble("destination_latitude"),
                        resultSet.getDouble("destination_longitude"))),
                dispatchPlanId);
    }

    public void releaseReservations(UUID dispatchPlanId) {
        jdbcTemplate.update("DELETE FROM dispatch_package_reservation WHERE dispatch_plan_id = ?", dispatchPlanId);
        jdbcTemplate.update("DELETE FROM dispatch_day_reservation WHERE dispatch_plan_id = ?", dispatchPlanId);
    }

    private DispatchPlanRecord mapPlan(ResultSet resultSet) throws SQLException {
        return new DispatchPlanRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("service_date", LocalDate.class),
                DispatchStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("created_by_account_id", UUID.class),
                resultSet.getString("idempotency_key"),
                resultSet.getString("request_hash"),
                new Location(
                        resultSet.getString("hub_label"),
                        resultSet.getString("hub_query"),
                        resultSet.getDouble("hub_latitude"),
                        resultSet.getDouble("hub_longitude")),
                resultSet.getString("input_snapshot_json"),
                resultSet.getString("policy_snapshot_json"),
                resultSet.getString("result_json"),
                resultSet.getString("hub_execution_json"),
                resultSet.getString("metrics_json"),
                resultSet.getString("result_status"),
                resultSet.getString("solver_status"),
                resultSet.getInt("selected_package_count"),
                nullableInteger(resultSet, "assigned_package_count"),
                nullableInteger(resultSet, "unassigned_package_count"),
                resultSet.getInt("selected_courier_count"),
                nullableInteger(resultSet, "used_courier_count"),
                nullableLong(resultSet, "total_distance_meters"),
                nullableLong(resultSet, "total_duration_seconds"),
                instant(resultSet, "meeting_at"),
                instant(resultSet, "redistribution_completed_at"),
                instant(resultSet, "completed_at"),
                resultSet.getString("failure_code"),
                resultSet.getString("failure_message"),
                instant(resultSet, "created_at"),
                instant(resultSet, "completed_processing_at"));
    }

    private Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Timestamp timestamp(java.time.OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
