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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.delivery.domain.ConfirmedLocation;
import com.azylivraison.delivery.domain.DeliveryPackageStatus;
import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.delivery.domain.DeliveryRequestStatus;
import com.azylivraison.delivery.domain.ExpressDeliveryStatus;
import com.azylivraison.delivery.domain.PackageWeightSource;
import com.azylivraison.delivery.domain.StoredDeliveryPackage;
import com.azylivraison.delivery.domain.StoredDeliveryRequest;
import com.azylivraison.pricing.domain.DeliveryPricingSnapshot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryRequestRepository {

    private static final String REQUEST_SELECT = """
            SELECT dr.*, aa.display_name AS owner_display_name, aa.phone AS owner_phone
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

    public boolean isDateClosedForAdmissions(LocalDate date) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_operation_day
                WHERE service_date = ?
                  AND admissions_closed_at IS NOT NULL
                """, Integer.class, Date.valueOf(date));
        return count != null && count > 0;
    }

    public int countAllocatedPackages(LocalDate date) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_package dp
                JOIN delivery_request dr ON dr.id = dp.delivery_request_id
                WHERE dr.requested_date = ?
                  AND dr.request_kind = 'NORMAL'
                  AND dp.cancelled_before_processing_at IS NULL
                  AND (dp.final_outcome IS NULL OR dp.final_outcome <> 'CANCELLED')
                """, Integer.class, Date.valueOf(date));
        return count == null ? 0 : count;
    }

    public boolean canCreateExpress(UUID accountId) {
        Boolean allowed = jdbcTemplate.queryForObject(
                "SELECT express_delivery_enabled FROM client_profile WHERE account_id = ?",
                Boolean.class, accountId);
        return Boolean.TRUE.equals(allowed);
    }

    public void insert(StoredDeliveryRequest request, UUID actorAccountId) {
        jdbcTemplate.update("""
                INSERT INTO delivery_request (
                    id, owner_account_id, requested_date,
                    request_kind, express_status, created_by_account_id,
                    pickup_label, pickup_query, pickup_latitude, pickup_longitude,
                    capacity_limit_snapshot, capacity_current_snapshot,
                    capacity_requested_snapshot, capacity_projected_snapshot,
                    capacity_overflow_warning, capacity_overflow_accepted,
                    status, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.id(), request.ownerAccountId(), Date.valueOf(request.requestedDate()),
                request.requestKind().name(),
                request.expressStatus() == null ? null : request.expressStatus().name(),
                request.createdByAccountId(),
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
                REQUEST_SELECT + """
                         WHERE dr.owner_account_id = ?
                           AND dr.cancelled_before_processing_at IS NULL
                           AND EXISTS (SELECT 1 FROM delivery_package visible_package
                                       WHERE visible_package.delivery_request_id = dr.id
                                         AND visible_package.cancelled_before_processing_at IS NULL)
                         ORDER BY dr.created_at DESC
                        """,
                this::mapRequestWithoutPackages,
                ownerAccountId);
        return withPackages(requests);
    }

    public Optional<StoredDeliveryRequest> findByIdAndOwner(UUID id, UUID ownerAccountId) {
        List<StoredDeliveryRequest> requests = jdbcTemplate.query(
                REQUEST_SELECT + " WHERE dr.id = ? AND dr.owner_account_id = ? AND dr.cancelled_before_processing_at IS NULL",
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
            LocalDate from,
            LocalDate to,
            DeliveryRequestStatus status,
            String clientQuery,
            boolean pendingOnly) {
        StringBuilder sql = new StringBuilder(REQUEST_SELECT)
                .append("""
                         WHERE dr.cancelled_before_processing_at IS NULL
                           AND EXISTS (
                               SELECT 1 FROM delivery_package visible_package
                               WHERE visible_package.delivery_request_id = dr.id
                                 AND visible_package.cancelled_before_processing_at IS NULL
                           )
                        """);
        List<Object> arguments = new ArrayList<>();
        if (date != null) {
            sql.append(" AND dr.requested_date = ?");
            arguments.add(Date.valueOf(date));
        }
        if (from != null) {
            sql.append(" AND dr.requested_date >= ?");
            arguments.add(Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND dr.requested_date <= ?");
            arguments.add(Date.valueOf(to));
        }
        if (status != null) {
            if (status == DeliveryRequestStatus.CANCELLED) {
                sql.append("""
                         AND EXISTS (
                             SELECT 1 FROM delivery_package cancelled_package
                             WHERE cancelled_package.delivery_request_id = dr.id
                         )
                         AND NOT EXISTS (
                             SELECT 1 FROM delivery_package active_package
                             WHERE active_package.delivery_request_id = dr.id
                               AND (active_package.final_outcome IS NULL
                                    OR active_package.final_outcome <> 'CANCELLED')
                         )
                        """);
            } else {
                sql.append(" AND dr.status = ?");
                arguments.add(status.name());
                if (status == DeliveryRequestStatus.PENDING) {
                    sql.append("""
                             AND EXISTS (
                                 SELECT 1 FROM delivery_package active_pending_package
                             WHERE active_pending_package.delivery_request_id = dr.id
                               AND active_pending_package.cancelled_before_processing_at IS NULL
                               AND active_pending_package.final_outcome IS NULL
                             )
                            """);
                }
            }
        }
        if (pendingOnly) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1
                         FROM delivery_package pending_package
                         WHERE pending_package.delivery_request_id = dr.id
                           AND pending_package.cancelled_before_processing_at IS NULL
                           AND pending_package.status = 'PENDING'
                           AND pending_package.final_outcome IS NULL
                     )
                    """);
        }
        if (clientQuery != null && !clientQuery.isBlank()) {
            String pattern = containsPattern(clientQuery);
            sql.append("""
                     AND (
                         LOWER(aa.display_name) LIKE ? ESCAPE '\\'
                         OR LOWER(aa.username) LIKE ? ESCAPE '\\'
                         OR LOWER(COALESCE(aa.phone, '')) LIKE ? ESCAPE '\\'
                     )
                    """);
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        sql.append(" ORDER BY dr.requested_date, dr.created_at, dr.id");
        List<StoredDeliveryRequest> requests = jdbcTemplate.query(
                sql.toString(), this::mapRequestWithoutPackages, arguments.toArray());
        return withPackages(requests);
    }

    private String containsPattern(String value) {
        String escaped = value.strip()
                .toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** Returns only the admitted queue for the selected activity date. */
    public List<StoredDeliveryRequest> findPendingQueueForDate(LocalDate date) {
        List<StoredDeliveryRequest> requests = jdbcTemplate.query(
                REQUEST_SELECT + """
                         WHERE dr.requested_date = ?
                           AND dr.request_kind = 'NORMAL'
                           AND dr.cancelled_before_processing_at IS NULL
                           AND EXISTS (
                               SELECT 1
                               FROM delivery_package queued_package
                               WHERE queued_package.delivery_request_id = dr.id
                                 AND queued_package.cancelled_before_processing_at IS NULL
                                 AND queued_package.status = 'PENDING'
                                 AND queued_package.final_outcome IS NULL
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
                    COUNT(DISTINCT CASE
                        WHEN dr.status = 'PENDING'
                         AND dp.status = 'PENDING'
                         AND dp.final_outcome IS NULL
                        THEN dr.id END) AS pending_requests,
                    COUNT(DISTINCT CASE
                        WHEN dr.status = 'PARTIALLY_DISPATCHED'
                         AND dp.status = 'PENDING'
                         AND dp.final_outcome IS NULL
                        THEN dr.id END)
                        AS partially_dispatched_requests,
                    COUNT(CASE WHEN dp.status = 'PENDING' AND dp.final_outcome IS NULL THEN 1 END)
                        AS pending_packages
                FROM delivery_request dr
                LEFT JOIN delivery_package dp
                       ON dp.delivery_request_id = dr.id
                      AND dp.cancelled_before_processing_at IS NULL
                WHERE dr.requested_date = ?
                  AND dr.request_kind = 'NORMAL'
                  AND dr.cancelled_before_processing_at IS NULL
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
                    desired_delivery_time,
                    pricing_zone_id, pricing_zone_name, delivery_fee_ariary,
                    final_delivery_fee_ariary, delivery_fee_override_reason,
                    delivery_fee_overridden_at, delivery_fee_overridden_by_account_id,
                    delivery_fee_version,
                    distance_from_center_meters, pricing_zone_max_distance_meters,
                    pricing_policy_version, pricing_policy_id,
                    base_delivery_fee_ariary, weight_surcharge_ariary, time_surcharge_ariary,
                    effective_weight_kg, weight_source,
                    status, unassigned_reason,
                    final_outcome, actual_collected_ariary, outcome_note,
                    outcome_recorded_at, outcome_recorded_by_account_id,
                    return_completed_at, return_completed_by_account_id,
                    carried_forward_from_package_id, dispatch_plan_id, priority_at,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                deliveryPackage.id(), deliveryPackage.deliveryRequestId(), deliveryPackage.sequenceNumber(),
                deliveryPackage.recipientName(), deliveryPackage.faritra(),
                deliveryPackage.destination().label(), deliveryPackage.destination().query(),
                deliveryPackage.destination().latitude(), deliveryPackage.destination().longitude(),
                deliveryPackage.contact(), deliveryPackage.amountToCollectAriary(),
                deliveryPackage.mobileMoneyPaid(),
                deliveryPackage.desiredDeliveryTime(),
                deliveryPackage.pricingSnapshot().pricingZoneId(),
                deliveryPackage.pricingSnapshot().pricingZoneName(),
                deliveryPackage.pricingSnapshot().deliveryFeeAriary(),
                deliveryPackage.finalDeliveryFeeAriary(),
                deliveryPackage.deliveryFeeOverrideReason(),
                timestamp(deliveryPackage.deliveryFeeOverriddenAt()),
                deliveryPackage.deliveryFeeOverriddenByAccountId(),
                deliveryPackage.deliveryFeeVersion(),
                deliveryPackage.pricingSnapshot().distanceFromCenterMeters(),
                deliveryPackage.pricingSnapshot().pricingZoneMaxDistanceMeters(),
                deliveryPackage.pricingSnapshot().pricingPolicyVersion(),
                deliveryPackage.pricingSnapshot().pricingPolicyId(),
                deliveryPackage.pricingSnapshot().baseDeliveryFeeAriary(),
                deliveryPackage.pricingSnapshot().weightSurchargeAriary(),
                deliveryPackage.pricingSnapshot().timeSurchargeAriary(),
                deliveryPackage.effectiveWeightKg(),
                deliveryPackage.weightSource().name(), deliveryPackage.status().name(),
                deliveryPackage.unassignedReason(),
                deliveryPackage.finalOutcome() == null ? null : deliveryPackage.finalOutcome().name(),
                deliveryPackage.actualCollectedAriary(), deliveryPackage.outcomeNote(),
                timestamp(deliveryPackage.outcomeRecordedAt()), deliveryPackage.outcomeRecordedByAccountId(),
                timestamp(deliveryPackage.returnCompletedAt()), deliveryPackage.returnCompletedByAccountId(),
                deliveryPackage.carriedForwardFromPackageId(), deliveryPackage.dispatchPlanId(),
                timestamp(deliveryPackage.priorityAt()), deliveryPackage.version(),
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
                .map(request -> {
                    List<StoredDeliveryPackage> packages = findPackages(request.id());
                    DeliveryRequestStatus effectiveStatus = !packages.isEmpty()
                            && packages.stream().allMatch(deliveryPackage ->
                                    deliveryPackage.finalOutcome()
                                            == com.azylivraison.delivery.domain.DeliveryPackageOutcome.CANCELLED)
                            ? DeliveryRequestStatus.CANCELLED
                            : request.status();
                    return new StoredDeliveryRequest(
                            request.id(), request.ownerAccountId(), request.ownerDisplayName(), request.ownerPhone(),
                            request.requestKind(), request.expressStatus(), request.createdByAccountId(),
                            request.requestedDate(),
                            request.pickup(), request.capacityLimitSnapshot(), request.capacityCurrentSnapshot(),
                            request.capacityRequestedSnapshot(), request.capacityProjectedSnapshot(),
                            request.capacityOverflowWarning(), request.capacityOverflowAccepted(), effectiveStatus,
                            request.version(), request.createdAt(), request.updatedAt(), packages);
                })
                .toList();
    }

    private List<StoredDeliveryPackage> findPackages(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT * FROM delivery_package
                WHERE delivery_request_id = ? AND cancelled_before_processing_at IS NULL
                ORDER BY sequence_number
                """, this::mapPackage, requestId);
    }

    private StoredDeliveryRequest mapRequestWithoutPackages(ResultSet resultSet, int rowNumber) throws SQLException {
        UUID ownerAccountId = resultSet.getObject("owner_account_id", UUID.class);
        UUID createdByAccountId = resultSet.getObject("created_by_account_id", UUID.class);
        return new StoredDeliveryRequest(
                resultSet.getObject("id", UUID.class),
                ownerAccountId,
                resultSet.getString("owner_display_name"),
                resultSet.getString("owner_phone"),
                DeliveryRequestKind.valueOf(resultSet.getString("request_kind")),
                resultSet.getString("express_status") == null ? null
                        : ExpressDeliveryStatus.valueOf(resultSet.getString("express_status")),
                createdByAccountId == null ? ownerAccountId : createdByAccountId,
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
                resultSet.getObject("desired_delivery_time", java.time.LocalTime.class),
                new DeliveryPricingSnapshot(
                        resultSet.getObject("pricing_zone_id", UUID.class),
                        resultSet.getString("pricing_zone_name"),
                        nullableLong(resultSet, "delivery_fee_ariary"),
                        nullableLong(resultSet, "distance_from_center_meters"),
                        nullableLong(resultSet, "pricing_zone_max_distance_meters"),
                        nullableLong(resultSet, "pricing_policy_version"),
                        resultSet.getObject("pricing_policy_id", Short.class),
                        nullableLong(resultSet, "base_delivery_fee_ariary"),
                        nullableLong(resultSet, "weight_surcharge_ariary"),
                        nullableLong(resultSet, "time_surcharge_ariary")),
                coalesce(
                        nullableLong(resultSet, "final_delivery_fee_ariary"),
                        nullableLong(resultSet, "delivery_fee_ariary")),
                resultSet.getString("delivery_fee_override_reason"),
                instant(resultSet, "delivery_fee_overridden_at"),
                resultSet.getObject("delivery_fee_overridden_by_account_id", UUID.class),
                resultSet.getLong("delivery_fee_version"),
                resultSet.getBigDecimal("effective_weight_kg"),
                PackageWeightSource.valueOf(resultSet.getString("weight_source")),
                DeliveryPackageStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("unassigned_reason"),
                resultSet.getString("final_outcome") == null
                        ? null
                        : com.azylivraison.delivery.domain.DeliveryPackageOutcome.valueOf(
                                resultSet.getString("final_outcome")),
                nullableLong(resultSet, "actual_collected_ariary"),
                resultSet.getString("outcome_note"),
                instant(resultSet, "outcome_recorded_at"),
                resultSet.getObject("outcome_recorded_by_account_id", UUID.class),
                instant(resultSet, "return_completed_at"),
                resultSet.getObject("return_completed_by_account_id", UUID.class),
                resultSet.getObject("carried_forward_from_package_id", UUID.class),
                resultSet.getObject("dispatch_plan_id", UUID.class),
                resultSet.getTimestamp("priority_at").toInstant(),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Long coalesce(Long preferred, Long fallback) {
        return preferred == null ? fallback : preferred;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public record OperationalSettingsSnapshot(int dailyPackageLimit, BigDecimal defaultPackageWeightKg) {
    }

    public record QueueCounts(
            int pendingRequestCount,
            int partiallyDispatchedRequestCount,
            int pendingPackageCount) {
    }
}
