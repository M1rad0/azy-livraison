package com.azylivraison.delivery.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.delivery.domain.ConfirmedLocation;
import com.azylivraison.delivery.domain.DeliveryPackageStatus;
import com.azylivraison.delivery.domain.DeliveryRequestStatus;
import com.azylivraison.delivery.domain.PackageWeightSource;
import com.azylivraison.delivery.domain.StoredDeliveryPackage;
import com.azylivraison.delivery.domain.StoredDeliveryRequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryRequestRepository {

    private static final String REQUEST_SELECT = """
            SELECT dr.*, aa.display_name AS owner_display_name
            FROM delivery_request dr
            JOIN app_account aa ON aa.id = dr.owner_account_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public DeliveryRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OperationalSettingsSnapshot lockOperationalSettings() {
        return jdbcTemplate.queryForObject("""
                SELECT daily_package_limit, default_package_weight_kg
                FROM operational_settings
                WHERE id = 1
                FOR UPDATE
                """, (resultSet, rowNumber) -> new OperationalSettingsSnapshot(
                resultSet.getInt("daily_package_limit"),
                resultSet.getBigDecimal("default_package_weight_kg")));
    }

    public OperationalSettingsSnapshot getOperationalSettings() {
        return jdbcTemplate.queryForObject("""
                SELECT daily_package_limit, default_package_weight_kg
                FROM operational_settings
                WHERE id = 1
                """, (resultSet, rowNumber) -> new OperationalSettingsSnapshot(
                resultSet.getInt("daily_package_limit"),
                resultSet.getBigDecimal("default_package_weight_kg")));
    }

    public int countPendingPackages(LocalDate date) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_package dp
                JOIN delivery_request dr ON dr.id = dp.delivery_request_id
                WHERE dr.requested_date = ? AND dp.status = 'PENDING'
                """, Integer.class, Date.valueOf(date));
        return count == null ? 0 : count;
    }

    public void insert(StoredDeliveryRequest request, UUID actorAccountId) {
        jdbcTemplate.update("""
                INSERT INTO delivery_request (
                    id, owner_account_id, requested_date,
                    pickup_label, pickup_query, pickup_latitude, pickup_longitude,
                    capacity_limit_snapshot, capacity_current_snapshot,
                    capacity_requested_snapshot, capacity_projected_snapshot,
                    capacity_overflow_warning, capacity_overflow_accepted,
                    status, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.id(), request.ownerAccountId(), Date.valueOf(request.requestedDate()),
                request.pickup().label(), request.pickup().query(),
                request.pickup().latitude(), request.pickup().longitude(),
                request.capacityLimitSnapshot(), request.capacityCurrentSnapshot(),
                request.capacityRequestedSnapshot(), request.capacityProjectedSnapshot(),
                request.capacityOverflowWarning(), request.capacityOverflowAccepted(),
                request.status().name(), request.version(),
                Timestamp.from(request.createdAt()), Timestamp.from(request.updatedAt()));

        for (StoredDeliveryPackage deliveryPackage : request.packages()) {
            insertPackage(deliveryPackage);
        }
        insertEvent(request.id(), null, "DELIVERY_REQUEST_CREATED", null,
                request.status().name(), actorAccountId,
                "{\"packageCount\":" + request.packages().size()
                        + ",\"capacityOverflowAccepted\":" + request.capacityOverflowAccepted() + "}",
                request.createdAt());
    }

    public void saveDefaultPickup(UUID accountId, ConfirmedLocation pickup, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE client_profile
                SET default_pickup_label = ?, default_pickup_query = ?,
                    default_pickup_latitude = ?, default_pickup_longitude = ?,
                    updated_at = ?, version = version + 1
                WHERE account_id = ?
                """,
                pickup.label(), pickup.query(), pickup.latitude(), pickup.longitude(),
                Timestamp.from(now), accountId);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO client_profile (
                        account_id, default_pickup_label, default_pickup_query,
                        default_pickup_latitude, default_pickup_longitude,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    accountId, pickup.label(), pickup.query(), pickup.latitude(), pickup.longitude(),
                    Timestamp.from(now), Timestamp.from(now));
        }
    }

    public List<StoredDeliveryRequest> findByOwner(UUID ownerAccountId) {
        List<StoredDeliveryRequest> requests = jdbcTemplate.query(
                REQUEST_SELECT + " WHERE dr.owner_account_id = ? ORDER BY dr.created_at DESC",
                this::mapRequestWithoutPackages,
                ownerAccountId);
        return withPackages(requests);
    }

    public Optional<StoredDeliveryRequest> findByIdAndOwner(UUID id, UUID ownerAccountId) {
        List<StoredDeliveryRequest> requests = jdbcTemplate.query(
                REQUEST_SELECT + " WHERE dr.id = ? AND dr.owner_account_id = ?",
                this::mapRequestWithoutPackages,
                id, ownerAccountId);
        return withPackages(requests).stream().findFirst();
    }

    public Optional<StoredDeliveryRequest> findById(UUID id) {
        List<StoredDeliveryRequest> requests = jdbcTemplate.query(
                REQUEST_SELECT + " WHERE dr.id = ?",
                this::mapRequestWithoutPackages,
                id);
        return withPackages(requests).stream().findFirst();
    }

    public List<StoredDeliveryRequest> findForAdministration(
            LocalDate date,
            DeliveryRequestStatus status) {
        StringBuilder sql = new StringBuilder(REQUEST_SELECT).append(" WHERE 1 = 1");
        List<Object> arguments = new ArrayList<>();
        if (date != null) {
            sql.append(" AND dr.requested_date = ?");
            arguments.add(Date.valueOf(date));
        }
        if (status != null) {
            sql.append(" AND dr.status = ?");
            arguments.add(status.name());
        }
        sql.append(" ORDER BY dr.requested_date, dr.created_at, dr.id");
        List<StoredDeliveryRequest> requests = jdbcTemplate.query(
                sql.toString(), this::mapRequestWithoutPackages, arguments.toArray());
        return withPackages(requests);
    }

    /**
     * Returns the operational backlog for a service day. An undelivered parcel
     * from an earlier requested date must remain visible until it is dispatched.
     */
    public List<StoredDeliveryRequest> findPendingQueueThrough(LocalDate date) {
        List<StoredDeliveryRequest> requests = jdbcTemplate.query(
                REQUEST_SELECT + """
                         WHERE dr.requested_date <= ?
                           AND EXISTS (
                               SELECT 1
                               FROM delivery_package queued_package
                               WHERE queued_package.delivery_request_id = dr.id
                                 AND queued_package.status = 'PENDING'
                           )
                         ORDER BY dr.requested_date, dr.created_at, dr.id
                        """,
                this::mapRequestWithoutPackages,
                Date.valueOf(date));
        return withPackages(requests);
    }

    public QueueCounts queueCounts(LocalDate date) {
        return jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(DISTINCT CASE WHEN dr.status = 'PENDING' THEN dr.id END) AS pending_requests,
                    COUNT(DISTINCT CASE WHEN dr.status = 'PARTIALLY_DISPATCHED' THEN dr.id END)
                        AS partially_dispatched_requests,
                    COUNT(CASE WHEN dp.status = 'PENDING' THEN 1 END) AS pending_packages
                FROM delivery_request dr
                LEFT JOIN delivery_package dp ON dp.delivery_request_id = dr.id
                WHERE dr.requested_date <= ?
                """, (resultSet, rowNumber) -> new QueueCounts(
                resultSet.getInt("pending_requests"),
                resultSet.getInt("partially_dispatched_requests"),
                resultSet.getInt("pending_packages")),
                Date.valueOf(date));
    }

    private void insertPackage(StoredDeliveryPackage deliveryPackage) {
        jdbcTemplate.update("""
                INSERT INTO delivery_package (
                    id, delivery_request_id, sequence_number,
                    recipient_name, faritra,
                    destination_label, destination_query,
                    destination_latitude, destination_longitude,
                    contact, amount_to_collect_ariary, mobile_money_paid,
                    effective_weight_kg, weight_source,
                    status, unassigned_reason, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                deliveryPackage.id(), deliveryPackage.deliveryRequestId(), deliveryPackage.sequenceNumber(),
                deliveryPackage.recipientName(), deliveryPackage.faritra(),
                deliveryPackage.destination().label(), deliveryPackage.destination().query(),
                deliveryPackage.destination().latitude(), deliveryPackage.destination().longitude(),
                deliveryPackage.contact(), deliveryPackage.amountToCollectAriary(),
                deliveryPackage.mobileMoneyPaid(), deliveryPackage.effectiveWeightKg(),
                deliveryPackage.weightSource().name(), deliveryPackage.status().name(),
                deliveryPackage.unassignedReason(), deliveryPackage.version(),
                Timestamp.from(deliveryPackage.createdAt()), Timestamp.from(deliveryPackage.updatedAt()));
    }

    private void insertEvent(
            UUID requestId,
            UUID packageId,
            String eventType,
            String fromStatus,
            String toStatus,
            UUID actorAccountId,
            String payloadJson,
            Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO delivery_event (
                    id, delivery_request_id, delivery_package_id, event_type,
                    from_status, to_status, actor_account_id, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), requestId, packageId, eventType,
                fromStatus, toStatus, actorAccountId, payloadJson, Timestamp.from(createdAt));
    }

    private List<StoredDeliveryRequest> withPackages(List<StoredDeliveryRequest> requests) {
        return requests.stream()
                .map(request -> new StoredDeliveryRequest(
                        request.id(), request.ownerAccountId(), request.ownerDisplayName(), request.requestedDate(),
                        request.pickup(), request.capacityLimitSnapshot(), request.capacityCurrentSnapshot(),
                        request.capacityRequestedSnapshot(), request.capacityProjectedSnapshot(),
                        request.capacityOverflowWarning(), request.capacityOverflowAccepted(), request.status(),
                        request.version(), request.createdAt(), request.updatedAt(), findPackages(request.id())))
                .toList();
    }

    private List<StoredDeliveryPackage> findPackages(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT * FROM delivery_package
                WHERE delivery_request_id = ?
                ORDER BY sequence_number
                """, this::mapPackage, requestId);
    }

    private StoredDeliveryRequest mapRequestWithoutPackages(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredDeliveryRequest(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("owner_account_id", UUID.class),
                resultSet.getString("owner_display_name"),
                resultSet.getDate("requested_date").toLocalDate(),
                new ConfirmedLocation(
                        resultSet.getString("pickup_label"),
                        resultSet.getString("pickup_query"),
                        resultSet.getDouble("pickup_latitude"),
                        resultSet.getDouble("pickup_longitude")),
                resultSet.getInt("capacity_limit_snapshot"),
                resultSet.getInt("capacity_current_snapshot"),
                resultSet.getInt("capacity_requested_snapshot"),
                resultSet.getInt("capacity_projected_snapshot"),
                resultSet.getBoolean("capacity_overflow_warning"),
                resultSet.getBoolean("capacity_overflow_accepted"),
                DeliveryRequestStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                List.of());
    }

    private StoredDeliveryPackage mapPackage(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredDeliveryPackage(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("delivery_request_id", UUID.class),
                resultSet.getInt("sequence_number"),
                resultSet.getString("recipient_name"),
                resultSet.getBoolean("faritra"),
                new ConfirmedLocation(
                        resultSet.getString("destination_label"),
                        resultSet.getString("destination_query"),
                        resultSet.getDouble("destination_latitude"),
                        resultSet.getDouble("destination_longitude")),
                resultSet.getString("contact"),
                resultSet.getLong("amount_to_collect_ariary"),
                resultSet.getBoolean("mobile_money_paid"),
                resultSet.getBigDecimal("effective_weight_kg"),
                PackageWeightSource.valueOf(resultSet.getString("weight_source")),
                DeliveryPackageStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("unassigned_reason"),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    public record OperationalSettingsSnapshot(int dailyPackageLimit, BigDecimal defaultPackageWeightKg) {
    }

    public record QueueCounts(
            int pendingRequestCount,
            int partiallyDispatchedRequestCount,
            int pendingPackageCount) {
    }
}
