package com.azylivraison.delivery.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.azylivraison.delivery.domain.ExpressFuelCalculation;
import com.azylivraison.delivery.domain.ExpressFuelCalculationStatus;
import com.azylivraison.delivery.domain.ExpressFuelLine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExpressFuelRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExpressFuelRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isCompletedWithFinalOutcomes(UUID requestId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_request request
                WHERE request.id = ? AND request.request_kind = 'EXPRESS'
                  AND request.express_status = 'COMPLETED'
                  AND request.cancelled_before_processing_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM delivery_package package
                      WHERE package.delivery_request_id = request.id
                        AND package.cancelled_before_processing_at IS NULL
                        AND package.final_outcome IS NULL
                  )
                """, Integer.class, requestId);
        return count != null && count == 1;
    }

    public Optional<RequestSnapshot> requestSnapshot(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT request.id, account.display_name AS client_display_name,
                       request.pickup_label, request.pickup_latitude, request.pickup_longitude,
                       request.express_return_to_pickup, request.requested_date,
                       request.express_status,
                       (SELECT MIN(selected.selected_at)
                        FROM express_delivery_courier selected
                        WHERE selected.delivery_request_id = request.id) AS confirmed_at
                FROM delivery_request request
                JOIN app_account account ON account.id = request.owner_account_id
                WHERE request.id = ? AND request.request_kind = 'EXPRESS'
                  AND request.cancelled_before_processing_at IS NULL
                """, (rs, row) -> mapRequestSnapshot(rs), requestId)
                .stream().findFirst();
    }

    public List<DeliveryStop> deliveryStops(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT package.id, package.destination_label,
                       package.destination_latitude, package.destination_longitude,
                       package.actual_delivery_courier_id
                FROM delivery_package package
                WHERE package.delivery_request_id = ?
                  AND package.cancelled_before_processing_at IS NULL
                  AND package.final_outcome IN ('DELIVERED', 'RETURN_TO_SENDER')
                ORDER BY package.sequence_number, package.id
                """, (rs, row) -> new DeliveryStop(
                rs.getObject("id", UUID.class), rs.getString("destination_label"),
                rs.getDouble("destination_latitude"), rs.getDouble("destination_longitude"),
                rs.getObject("actual_delivery_courier_id", UUID.class)), requestId);
    }

    /**
     * Couriers whose position may be affected by a request that is not complete
     * yet. The final courier can legitimately differ from the couriers selected
     * at confirmation time, hence the union of both sources.
     */
    public List<UUID> involvedCourierIds(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT courier_id
                FROM express_delivery_courier
                WHERE delivery_request_id = ?
                UNION
                SELECT actual_delivery_courier_id AS courier_id
                FROM delivery_package
                WHERE delivery_request_id = ?
                  AND actual_delivery_courier_id IS NOT NULL
                """, (rs, row) -> rs.getObject("courier_id", UUID.class), requestId, requestId);
    }

    public void ensure(UUID requestId, boolean returnToPickup) {
        jdbcTemplate.update("""
                INSERT INTO express_fuel_calculation (
                    delivery_request_id, status, return_to_pickup
                ) VALUES (?, 'WAITING_FOR_OUTCOMES', ?)
                ON CONFLICT (delivery_request_id) DO NOTHING
                """, requestId, returnToPickup);
    }

    public Optional<ExpressFuelCalculation> find(UUID requestId) {
        Optional<ExpressFuelCalculation> calculation = jdbcTemplate.query("""
                SELECT calculation.*, account.display_name AS client_display_name,
                       request.pickup_label
                FROM express_fuel_calculation calculation
                JOIN delivery_request request ON request.id = calculation.delivery_request_id
                JOIN app_account account ON account.id = request.owner_account_id
                WHERE calculation.delivery_request_id = ?
                """, (rs, row) -> mapCalculation(rs, List.of()), requestId).stream().findFirst();
        return calculation.map(item -> new ExpressFuelCalculation(
                item.requestId(), item.clientDisplayName(), item.pickupLabel(), item.status(),
                item.returnToPickup(), item.failureReason(), item.totalDistanceMeters(),
                item.totalSuggestedFuelAriary(), item.totalFinalFuelAriary(), item.version(),
                item.calculatedAt(), findLines(item.requestId())));
    }

    public List<ExpressFuelCalculation> findForDate(LocalDate serviceDate) {
        List<UUID> ids = jdbcTemplate.query("""
                SELECT request.id
                FROM delivery_request request
                WHERE request.request_kind = 'EXPRESS'
                  AND request.express_status = 'COMPLETED'
                  AND request.cancelled_before_processing_at IS NULL
                  AND request.requested_date = ?
                ORDER BY request.created_at, request.id
                """, (rs, row) -> rs.getObject(1, UUID.class), java.sql.Date.valueOf(serviceDate));
        return ids.stream().map(this::find).flatMap(Optional::stream).toList();
    }

    public List<RequestSnapshot> completedRequestsForDate(LocalDate serviceDate) {
        return requestsForChainDate(serviceDate).stream().filter(RequestSnapshot::completed).toList();
    }

    public List<RequestSnapshot> completedRequestsForDateAndClient(
            LocalDate serviceDate, UUID clientAccountId) {
        return jdbcTemplate.query("""
                SELECT request.id, account.display_name AS client_display_name,
                       request.pickup_label, request.pickup_latitude, request.pickup_longitude,
                       request.express_return_to_pickup, request.requested_date,
                       request.express_status,
                       (SELECT MIN(selected.selected_at)
                        FROM express_delivery_courier selected
                        WHERE selected.delivery_request_id = request.id) AS confirmed_at
                FROM delivery_request request
                JOIN app_account account ON account.id = request.owner_account_id
                WHERE request.request_kind = 'EXPRESS'
                  AND request.express_status = 'COMPLETED'
                  AND request.cancelled_before_processing_at IS NULL
                  AND request.requested_date = ?
                  AND request.owner_account_id = ?
                ORDER BY confirmed_at, request.created_at, request.id
                """, (rs, row) -> mapRequestSnapshot(rs),
                java.sql.Date.valueOf(serviceDate), clientAccountId);
    }

    public List<RequestSnapshot> requestsForChainDate(LocalDate serviceDate) {
        return jdbcTemplate.query("""
                SELECT request.id, account.display_name AS client_display_name,
                       request.pickup_label, request.pickup_latitude, request.pickup_longitude,
                       request.express_return_to_pickup, request.requested_date,
                       request.express_status,
                       (SELECT MIN(selected.selected_at)
                        FROM express_delivery_courier selected
                        WHERE selected.delivery_request_id = request.id) AS confirmed_at
                FROM delivery_request request
                JOIN app_account account ON account.id = request.owner_account_id
                WHERE request.request_kind = 'EXPRESS'
                  AND request.express_status IN ('CONFIRMED', 'COMPLETED')
                  AND request.cancelled_before_processing_at IS NULL
                  AND request.requested_date = ?
                ORDER BY confirmed_at, request.created_at, request.id
                """, (rs, row) -> mapRequestSnapshot(rs),
                java.sql.Date.valueOf(serviceDate));
    }

    public Optional<LocationSnapshot> packageLocation(UUID packageId) {
        return jdbcTemplate.query("""
                SELECT destination_latitude, destination_longitude
                FROM delivery_package WHERE id = ?
                """, (rs, row) -> new LocationSnapshot(
                rs.getDouble("destination_latitude"), rs.getDouble("destination_longitude")), packageId)
                .stream().findFirst();
    }

    public int unresolvedCompletedCount(LocalDate serviceDate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_request request
                LEFT JOIN express_fuel_calculation calculation
                       ON calculation.delivery_request_id = request.id
                WHERE request.request_kind = 'EXPRESS'
                  AND request.express_status = 'COMPLETED'
                  AND request.cancelled_before_processing_at IS NULL
                  AND request.requested_date = ?
                  AND (calculation.delivery_request_id IS NULL
                       OR calculation.status NOT IN ('CALCULATED', 'MANUAL'))
                """, Integer.class, java.sql.Date.valueOf(serviceDate));
        return count == null ? 0 : count;
    }

    public boolean isFinanciallyFinalized(UUID requestId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_request request
                WHERE request.id = ?
                  AND (
                      EXISTS (
                          SELECT 1 FROM courier_fuel_earning earning
                          WHERE earning.delivery_request_id = request.id
                      )
                      OR EXISTS (
                          SELECT 1 FROM courier_compensation_batch batch
                          WHERE batch.service_date = request.requested_date
                            AND (batch.client_account_id = request.owner_account_id
                                 OR batch.client_account_id IS NULL)
                      )
                  )
                """, Integer.class, requestId);
        return count != null && count > 0;
    }

    public boolean replace(UUID requestId, long expectedVersion,
            ExpressFuelCalculationStatus status, boolean returnToPickup,
            List<ExpressFuelLine> lines, Instant now) {
        long totalDistance = lines.stream().mapToLong(ExpressFuelLine::finalDistanceMeters)
                .reduce(0, Math::addExact);
        long totalSuggested = lines.stream().mapToLong(ExpressFuelLine::suggestedFuelAriary)
                .reduce(0, Math::addExact);
        long totalFinal = lines.stream().mapToLong(ExpressFuelLine::finalFuelAriary)
                .reduce(0, Math::addExact);
        int updated = jdbcTemplate.update("""
                UPDATE express_fuel_calculation
                SET status = ?, return_to_pickup = ?, failure_reason = NULL,
                    total_distance_meters = ?, total_suggested_fuel_ariary = ?,
                    total_final_fuel_ariary = ?, calculated_at = ?,
                    version = version + 1, updated_at = ?
                WHERE delivery_request_id = ? AND version = ?
                """, status.name(), returnToPickup, totalDistance, totalSuggested, totalFinal,
                Timestamp.from(now), Timestamp.from(now), requestId, expectedVersion);
        if (updated == 0) return false;
        jdbcTemplate.update("DELETE FROM express_fuel_courier_calculation WHERE delivery_request_id = ?", requestId);
        lines.forEach(line -> insertLine(requestId, line, now));
        return true;
    }

    public boolean markFailure(UUID requestId, long expectedVersion, boolean returnToPickup,
            ExpressFuelCalculationStatus status, String reason, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE express_fuel_calculation
                SET status = ?, return_to_pickup = ?, failure_reason = ?,
                    total_distance_meters = 0, total_suggested_fuel_ariary = 0,
                    total_final_fuel_ariary = 0, calculated_at = ?,
                    version = version + 1, updated_at = ?
                WHERE delivery_request_id = ? AND version = ?
                """, status.name(), returnToPickup, reason, Timestamp.from(now), Timestamp.from(now),
                requestId, expectedVersion);
        if (updated == 1) {
            jdbcTemplate.update("DELETE FROM express_fuel_courier_calculation WHERE delivery_request_id = ?", requestId);
        }
        return updated == 1;
    }

    private void insertLine(UUID requestId, ExpressFuelLine line, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO express_fuel_courier_calculation (
                    delivery_request_id, courier_id, courier_name_snapshot,
                    courier_contact_snapshot, package_ids_json,
                    reposition_distance_meters, automatic_distance_meters, final_distance_meters,
                    fuel_reimbursement_enabled, fuel_efficiency_km_per_liter,
                    fuel_price_per_liter_ariary, suggested_fuel_ariary,
                    final_fuel_ariary, override_reason, manual, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, requestId, line.courierId(), line.courierName(), line.courierContact(),
                writeIds(line.packageIds()), line.repositionDistanceMeters(),
                line.automaticDistanceMeters(), line.finalDistanceMeters(),
                line.fuelReimbursementEnabled(), line.fuelEfficiencyKmPerLiter(),
                line.fuelPricePerLiterAriary(), line.suggestedFuelAriary(), line.finalFuelAriary(),
                line.overrideReason(), line.manual(), Timestamp.from(now), Timestamp.from(now));
    }

    private List<ExpressFuelLine> findLines(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT * FROM express_fuel_courier_calculation
                WHERE delivery_request_id = ?
                ORDER BY LOWER(courier_name_snapshot), courier_id
                """, (rs, row) -> new ExpressFuelLine(
                rs.getObject("courier_id", UUID.class), rs.getString("courier_name_snapshot"),
                rs.getString("courier_contact_snapshot"), readIds(rs.getString("package_ids_json")),
                nullableLong(rs, "reposition_distance_meters"),
                nullableLong(rs, "automatic_distance_meters"), rs.getLong("final_distance_meters"),
                rs.getBoolean("fuel_reimbursement_enabled"),
                rs.getBigDecimal("fuel_efficiency_km_per_liter"),
                rs.getLong("fuel_price_per_liter_ariary"), rs.getLong("suggested_fuel_ariary"),
                rs.getLong("final_fuel_ariary"), rs.getString("override_reason"),
                rs.getBoolean("manual")), requestId);
    }

    private ExpressFuelCalculation mapCalculation(ResultSet rs, List<ExpressFuelLine> lines) throws SQLException {
        return new ExpressFuelCalculation(
                rs.getObject("delivery_request_id", UUID.class), rs.getString("client_display_name"),
                rs.getString("pickup_label"),
                ExpressFuelCalculationStatus.valueOf(rs.getString("status")),
                rs.getBoolean("return_to_pickup"), rs.getString("failure_reason"),
                rs.getLong("total_distance_meters"), rs.getLong("total_suggested_fuel_ariary"),
                rs.getLong("total_final_fuel_ariary"), rs.getLong("version"),
                instant(rs, "calculated_at"), lines);
    }

    private RequestSnapshot mapRequestSnapshot(ResultSet rs) throws SQLException {
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        return new RequestSnapshot(
                rs.getObject("id", UUID.class), rs.getString("client_display_name"),
                rs.getString("pickup_label"), rs.getDouble("pickup_latitude"),
                rs.getDouble("pickup_longitude"), rs.getBoolean("express_return_to_pickup"),
                rs.getObject("requested_date", LocalDate.class),
                "COMPLETED".equals(rs.getString("express_status")),
                confirmedAt == null ? Instant.EPOCH : confirmedAt.toInstant());
    }

    private String writeIds(List<UUID> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Les colis du calcul carburant ne peuvent pas être enregistrés.", exception);
        }
    }

    private List<UUID> readIds(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<UUID>>() {});
        } catch (JacksonException exception) {
            throw new IllegalStateException("Les colis du calcul carburant ne peuvent pas être relus.", exception);
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record RequestSnapshot(UUID requestId, String clientDisplayName, String pickupLabel,
            double pickupLatitude, double pickupLongitude, boolean returnToPickup,
            LocalDate serviceDate, boolean completed, Instant confirmedAt) {}

    public record LocationSnapshot(double latitude, double longitude) {}

    public record DeliveryStop(UUID packageId, String label, double latitude, double longitude,
            UUID courierId) {}
}
