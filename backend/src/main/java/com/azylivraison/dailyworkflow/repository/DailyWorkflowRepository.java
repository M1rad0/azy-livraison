package com.azylivraison.dailyworkflow.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.dailyworkflow.domain.DailyPackageRecord;
import com.azylivraison.dailyworkflow.domain.DeliveryFeeMode;
import com.azylivraison.dailyworkflow.domain.OperationDay;
import com.azylivraison.dailyworkflow.domain.OperationDayStatus;
import com.azylivraison.delivery.domain.ConfirmedLocation;
import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.delivery.domain.DeliveryPackageStatus;
import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.delivery.domain.PackageWeightSource;
import com.azylivraison.pricing.domain.PricingDistanceMode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DailyWorkflowRepository {

    private static final String SELECT_DAY = """
            SELECT service_date, status, admissions_closed_at, dispatch_plan_id,
                   closure_revision, closed_at, closed_by_account_id,
                   reopened_at, reopened_by_account_id, reopen_reason,
                   version, created_at, updated_at
            FROM delivery_operation_day
            """;

    private final JdbcTemplate jdbcTemplate;

    public DailyWorkflowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockAdmissionGate() {
        jdbcTemplate.queryForObject(
                "SELECT id FROM operational_settings WHERE id = 1 FOR UPDATE", Integer.class);
    }

    public Optional<OperationDay> findDay(LocalDate serviceDate) {
        return jdbcTemplate.query(
                SELECT_DAY + " WHERE service_date = ?",
                (resultSet, rowNumber) -> mapDay(resultSet),
                Date.valueOf(serviceDate)).stream().findFirst();
    }

    public Optional<OperationDay> lockDay(LocalDate serviceDate) {
        return jdbcTemplate.query(
                SELECT_DAY + " WHERE service_date = ? FOR UPDATE",
                (resultSet, rowNumber) -> mapDay(resultSet),
                Date.valueOf(serviceDate)).stream().findFirst();
    }

    public List<DailyPackageRecord> findPackages(OperationDay day) {
        return jdbcTemplate.query("""
                SELECT p.id AS package_id, p.delivery_request_id,
                       dr.owner_account_id,
                       COALESCE(snapshot.display_name, account.display_name) AS owner_display_name,
                       COALESCE(snapshot.phone, account.phone) AS owner_phone,
                       dr.request_kind,
                       dr.requested_date,
                       dr.pickup_label, dr.pickup_query,
                       dr.pickup_latitude, dr.pickup_longitude,
                       p.sequence_number, p.recipient_name, p.faritra,
                       p.destination_label, p.destination_query,
                       p.destination_latitude, p.destination_longitude,
                       p.contact, p.amount_to_collect_ariary, p.mobile_money_paid,
                       p.pricing_zone_id, p.pricing_zone_name, p.delivery_fee_ariary,
                       p.distance_from_center_meters, p.pricing_distance_mode,
                       COALESCE(p.final_delivery_fee_ariary, p.delivery_fee_ariary) AS final_delivery_fee_ariary,
                       p.delivery_fee_override_reason, p.delivery_fee_overridden_at,
                       p.delivery_fee_version,
                       p.status, p.unassigned_reason, p.final_outcome,
                       p.actual_collected_ariary, p.outcome_note, p.outcome_recorded_at,
                       p.return_completed_at, p.carried_forward_from_package_id,
                       successor.id AS carried_forward_to_package_id,
                       p.dispatch_plan_id,
                       COALESCE(outcome.distributor_courier_id,
                                express_courier.courier_id) AS distributor_courier_id,
                       COALESCE(courier.courier_name,
                                express_courier.courier_name_snapshot) AS distributor_courier_name,
                       p.actual_delivery_courier_id, p.actual_delivery_courier_name,
                       p.actual_delivery_courier_contact, p.actual_delivery_vehicle_type,
                       outcome.estimated_delivery_at, p.priority_at, p.version
                FROM delivery_package p
                JOIN delivery_request dr ON dr.id = p.delivery_request_id
                JOIN app_account account ON account.id = dr.owner_account_id
                LEFT JOIN delivery_operation_client_snapshot snapshot
                       ON snapshot.service_date = ?
                      AND snapshot.closure_revision = ?
                      AND snapshot.account_id = dr.owner_account_id
                LEFT JOIN delivery_package successor
                       ON successor.carried_forward_from_package_id = p.id
                LEFT JOIN dispatch_package_outcome outcome
                       ON outcome.dispatch_plan_id = p.dispatch_plan_id
                      AND outcome.delivery_package_id = p.id
                LEFT JOIN dispatch_plan_courier courier
                       ON courier.dispatch_plan_id = outcome.dispatch_plan_id
                      AND courier.courier_id = outcome.distributor_courier_id
                LEFT JOIN express_delivery_courier express_courier
                       ON express_courier.delivery_request_id = dr.id
                      AND dr.request_kind = 'EXPRESS'
                      AND 1 = (
                          SELECT COUNT(*)
                          FROM express_delivery_courier selected_courier
                          WHERE selected_courier.delivery_request_id = dr.id
                      )
                WHERE p.cancelled_before_processing_at IS NULL
                  AND (p.dispatch_plan_id IN (
                        SELECT historical_plan.id
                        FROM dispatch_plan historical_plan
                        WHERE historical_plan.service_date = ?
                          AND historical_plan.status IN ('READY', 'READY_WITH_WARNINGS')
                          AND historical_plan.lifecycle_status = 'ACTIVE'
                    )
                   OR (dr.requested_date = ? AND p.final_outcome = 'CANCELLED')
                   OR (dr.requested_date = ? AND dr.request_kind = 'EXPRESS'
                       AND dr.express_status IN ('CONFIRMED', 'COMPLETED')))
                ORDER BY dr.owner_account_id, p.priority_at, dr.created_at,
                         p.sequence_number, p.id
                """, this::mapDailyPackage,
                Date.valueOf(day.serviceDate()),
                day.closureRevision(),
                Date.valueOf(day.serviceDate()),
                Date.valueOf(day.serviceDate()),
                Date.valueOf(day.serviceDate()));
    }

    public Optional<PackageForUpdate> lockPackage(UUID packageId) {
        return jdbcTemplate.query("""
                SELECT p.*, dr.owner_account_id, dr.requested_date, dr.request_kind
                FROM delivery_package p
                JOIN delivery_request dr ON dr.id = p.delivery_request_id
                WHERE p.id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> mapPackageForUpdate(resultSet), packageId)
                .stream().findFirst();
    }

    public DeliveryFeeRecord updateDeliveryFee(
            PackageForUpdate source,
            DeliveryFeeMode mode,
            Long finalFeeAriary,
            String reason,
            UUID actorAccountId,
            Instant now) {
        String storedReason = mode == DeliveryFeeMode.MANUAL ? reason : null;
        Instant overriddenAt = mode == DeliveryFeeMode.MANUAL ? now : null;
        UUID overriddenBy = mode == DeliveryFeeMode.MANUAL ? actorAccountId : null;
        int updated = jdbcTemplate.update("""
                UPDATE delivery_package
                SET final_delivery_fee_ariary = ?,
                    delivery_fee_override_reason = ?,
                    delivery_fee_overridden_at = ?,
                    delivery_fee_overridden_by_account_id = ?,
                    delivery_fee_version = delivery_fee_version + 1,
                    version = version + 1,
                    updated_at = ?
                WHERE id = ? AND version = ?
                """,
                finalFeeAriary,
                storedReason,
                timestamp(overriddenAt),
                overriddenBy,
                timestamp(now),
                source.packageId(),
                source.version());
        if (updated != 1) {
            throw new IllegalStateException("Le tarif du colis n'a pas pu être modifié.");
        }
        long nextFeeVersion = source.deliveryFeeVersion() + 1;
        jdbcTemplate.update("""
                INSERT INTO delivery_fee_change_event (
                    id, delivery_package_id, fee_version, change_mode,
                    calculated_fee_ariary, previous_final_fee_ariary,
                    new_final_fee_ariary, reason, actor_account_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), source.packageId(), nextFeeVersion, mode.name(),
                source.deliveryFeeAriary(), source.finalDeliveryFeeAriary(), finalFeeAriary,
                storedReason, actorAccountId, timestamp(now));
        return new DeliveryFeeRecord(
                source.packageId(), source.deliveryFeeAriary(), finalFeeAriary,
                storedReason, overriddenAt, overriddenBy,
                nextFeeVersion, source.version() + 1);
    }

    public boolean isAdmissionsClosed(LocalDate date) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM delivery_operation_day
                WHERE service_date = ? AND admissions_closed_at IS NOT NULL
                """, Integer.class, Date.valueOf(date));
        return count != null && count > 0;
    }

    public SettingsSnapshot settings() {
        return jdbcTemplate.queryForObject("""
                SELECT daily_package_limit, default_package_weight_kg
                FROM operational_settings WHERE id = 1
                """, (resultSet, rowNumber) -> new SettingsSnapshot(
                resultSet.getInt("daily_package_limit"),
                resultSet.getBigDecimal("default_package_weight_kg")));
    }

    public int countAllocatedPackages(LocalDate date) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_package p
                JOIN delivery_request dr ON dr.id = p.delivery_request_id
                WHERE dr.requested_date = ?
                  AND p.cancelled_before_processing_at IS NULL
                  AND (p.final_outcome IS NULL OR p.final_outcome <> 'CANCELLED')
                """, Integer.class, Date.valueOf(date));
        return count == null ? 0 : count;
    }

    public void cancelPackage(PackageForUpdate source, UUID actorAccountId, Instant now) {
        int cancelled = jdbcTemplate.update("""
                UPDATE delivery_package
                SET cancelled_before_processing_at = ?,
                    cancelled_before_processing_by_account_id = ?,
                    version = version + 1,
                    updated_at = ?
                WHERE id = ? AND final_outcome IS NULL AND status = 'PENDING'
                  AND cancelled_before_processing_at IS NULL
                """, timestamp(now), actorAccountId, timestamp(now), source.packageId());
        if (cancelled != 1) {
            throw new IllegalStateException("Le colis n'a pas pu être annulé.");
        }
        int requestUpdated = jdbcTemplate.update("""
                UPDATE delivery_request
                SET version = version + 1, updated_at = ?
                WHERE id = ?
                """, timestamp(now), source.deliveryRequestId());
        if (requestUpdated != 1) {
            throw new IllegalStateException("La demande du colis annulé est introuvable.");
        }
        jdbcTemplate.update("""
                UPDATE delivery_request request
                SET cancelled_before_processing_at = ?,
                    cancelled_before_processing_by_account_id = ?,
                    updated_at = ?
                WHERE request.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM delivery_package package
                      WHERE package.delivery_request_id = request.id
                        AND package.cancelled_before_processing_at IS NULL
                  )
                """, timestamp(now), actorAccountId, timestamp(now), source.deliveryRequestId());
        insertDeliveryEvent(source, "PACKAGE_CANCELLED_BEFORE_PROCESSING", null, "CANCELLED",
                actorAccountId, null, now);
    }

    public void updateOutcome(
            PackageForUpdate source,
            DeliveryPackageOutcome outcome,
            Long actualCollectedAriary,
            CourierSnapshot actualCourier,
            String note,
            UUID actorAccountId,
            Instant now) {
        jdbcTemplate.update("""
                UPDATE delivery_package
                SET final_outcome = ?,
                    actual_collected_ariary = ?,
                    outcome_note = ?,
                    outcome_recorded_at = ?,
                    outcome_recorded_by_account_id = ?,
                    actual_delivery_courier_id = ?,
                    actual_delivery_courier_name = ?,
                    actual_delivery_courier_contact = ?,
                    actual_delivery_vehicle_type = ?,
                    return_completed_at = CASE
                        WHEN ? = 'RETURN_TO_SENDER' THEN return_completed_at ELSE NULL END,
                    return_completed_by_account_id = CASE
                        WHEN ? = 'RETURN_TO_SENDER' THEN return_completed_by_account_id ELSE NULL END,
                    version = version + 1,
                    updated_at = ?
                WHERE id = ?
                """,
                outcome.name(), actualCollectedAriary, note,
                timestamp(now), actorAccountId,
                actualCourier == null ? null : actualCourier.courierId(),
                actualCourier == null ? null : actualCourier.name(),
                actualCourier == null ? null : actualCourier.contact(),
                actualCourier == null ? null : actualCourier.vehicleType(),
                outcome.name(), outcome.name(), timestamp(now), source.packageId());
        insertDeliveryEvent(
                source,
                "PACKAGE_OUTCOME_RECORDED",
                source.finalOutcome() == null ? source.technicalStatus().name() : source.finalOutcome().name(),
                outcome.name(),
                actorAccountId,
                "{\"actualCollectedAriary\":" + (actualCollectedAriary == null ? "null" : actualCollectedAriary)
                        + ",\"actualCourierId\":"
                        + (actualCourier == null ? "null" : "\"" + actualCourier.courierId() + "\"")
                        + ",\"actualCourierName\":"
                        + jsonString(actualCourier == null ? null : actualCourier.name())
                        + ",\"actualCourierContact\":"
                        + jsonString(actualCourier == null ? null : actualCourier.contact())
                        + ",\"actualCourierVehicleType\":"
                        + jsonString(actualCourier == null ? null : actualCourier.vehicleType())
                        + "}",
                now);
    }

    public Optional<CourierSnapshot> findCourierSnapshot(UUID courierId) {
        return jdbcTemplate.query("""
                SELECT id, name, contact, vehicle_type
                FROM courier_profile
                WHERE id = ?
                """, (resultSet, rowNumber) -> new CourierSnapshot(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("contact"),
                resultSet.getString("vehicle_type")), courierId).stream().findFirst();
    }

    public void markReturnCompleted(PackageForUpdate source, UUID actorAccountId, Instant now) {
        jdbcTemplate.update("""
                UPDATE delivery_package
                SET return_completed_at = ?, return_completed_by_account_id = ?,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND final_outcome = 'RETURN_TO_SENDER'
                  AND return_completed_at IS NULL
                """, timestamp(now), actorAccountId, timestamp(now), source.packageId());
        insertDeliveryEvent(
                source, "PACKAGE_RETURN_COMPLETED", "RETURN_TO_SENDER", "RETURN_TO_SENDER",
                actorAccountId, "{\"returnCompleted\":true}", now);
    }

    public int incompleteDayPackageCount(LocalDate serviceDate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_package package
                JOIN delivery_request request ON request.id = package.delivery_request_id
                LEFT JOIN dispatch_plan plan ON plan.id = package.dispatch_plan_id
                WHERE package.cancelled_before_processing_at IS NULL
                  AND ((plan.service_date = ?
                        AND plan.status IN ('READY', 'READY_WITH_WARNINGS')
                        AND plan.lifecycle_status = 'ACTIVE')
                       OR (request.requested_date = ? AND request.request_kind = 'EXPRESS'
                           AND request.express_status IN ('CONFIRMED', 'COMPLETED')))
                  AND package.final_outcome IS NULL
                """, Integer.class, Date.valueOf(serviceDate), Date.valueOf(serviceDate));
        return count == null ? 0 : count;
    }

    public int pendingExpressRequestCount(LocalDate serviceDate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM delivery_request
                WHERE requested_date = ? AND request_kind = 'EXPRESS'
                  AND express_status = 'PENDING' AND cancelled_before_processing_at IS NULL
                """, Integer.class, Date.valueOf(serviceDate));
        return count == null ? 0 : count;
    }

    public int undispatchedNormalPackageCount(LocalDate serviceDate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_package package
                JOIN delivery_request request ON request.id = package.delivery_request_id
                WHERE request.requested_date = ?
                  AND request.request_kind = 'NORMAL'
                  AND package.cancelled_before_processing_at IS NULL
                  AND (package.final_outcome IS NULL OR package.final_outcome <> 'CANCELLED')
                  AND (package.status <> 'DISPATCHED' OR package.dispatch_plan_id IS NULL)
                """, Integer.class, Date.valueOf(serviceDate));
        return count == null ? 0 : count;
    }

    public int uncarriedPostponedDayPackageCount(LocalDate serviceDate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_package source
                JOIN delivery_request request ON request.id = source.delivery_request_id
                JOIN dispatch_plan plan ON plan.id = source.dispatch_plan_id
                WHERE plan.service_date = ?
                  AND plan.status IN ('READY', 'READY_WITH_WARNINGS')
                  AND plan.lifecycle_status = 'ACTIVE'
                  AND request.request_kind = 'NORMAL'
                  AND source.final_outcome = 'POSTPONED'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM delivery_package successor
                      WHERE successor.carried_forward_from_package_id = source.id
                  )
                """, Integer.class, Date.valueOf(serviceDate));
        return count == null ? 0 : count;
    }

    public int missingActualCourierDayPackageCount(LocalDate serviceDate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_package package
                JOIN delivery_request request ON request.id = package.delivery_request_id
                LEFT JOIN dispatch_plan plan ON plan.id = package.dispatch_plan_id
                WHERE ((plan.service_date = ?
                        AND plan.status IN ('READY', 'READY_WITH_WARNINGS')
                        AND plan.lifecycle_status = 'ACTIVE')
                       OR (request.requested_date = ? AND request.request_kind = 'EXPRESS'
                           AND request.express_status IN ('CONFIRMED', 'COMPLETED')))
                  AND package.final_outcome IN ('DELIVERED', 'RETURN_TO_SENDER')
                  AND package.actual_delivery_courier_id IS NULL
                """, Integer.class, Date.valueOf(serviceDate), Date.valueOf(serviceDate));
        return count == null ? 0 : count;
    }

    public OperationDay close(OperationDay day, UUID actorAccountId, Instant now) {
        int nextRevision = Math.addExact(day.closureRevision(), 1);
        jdbcTemplate.update("""
                UPDATE delivery_operation_day
                SET status = 'CLOSED', closure_revision = ?,
                    admissions_closed_at = COALESCE(admissions_closed_at, ?),
                    closed_at = ?, closed_by_account_id = ?,
                    version = version + 1, updated_at = ?
                WHERE service_date = ?
                  AND ((status = 'DISPATCHED' AND dispatch_plan_id IS NOT NULL)
                       OR (status = 'OPEN' AND dispatch_plan_id IS NULL))
                  AND version = ?
                """, nextRevision, timestamp(now), timestamp(now), actorAccountId, timestamp(now),
                Date.valueOf(day.serviceDate()), day.version());
        snapshotClients(day.serviceDate(), nextRevision, now);
        insertDayEvent(day.serviceDate(), "DAY_CLOSED", actorAccountId,
                "{\"closureRevision\":" + nextRevision + "}", now);
        return lockDay(day.serviceDate()).orElseThrow();
    }

    public OperationDay reopen(
            OperationDay day,
            UUID actorAccountId,
            String reason,
            Instant now) {
        jdbcTemplate.update("""
                UPDATE delivery_operation_day
                SET status = CASE WHEN dispatch_plan_id IS NULL THEN 'OPEN' ELSE 'DISPATCHED' END,
                    admissions_closed_at = CASE
                        WHEN dispatch_plan_id IS NULL THEN NULL ELSE admissions_closed_at END,
                    reopened_at = ?, reopened_by_account_id = ?, reopen_reason = ?,
                    version = version + 1, updated_at = ?
                WHERE service_date = ? AND status = 'CLOSED' AND version = ?
                """, timestamp(now), actorAccountId, reason, timestamp(now),
                Date.valueOf(day.serviceDate()), day.version());
        insertDayEvent(day.serviceDate(), "DAY_REOPENED", actorAccountId,
                "{\"reason\":\"" + jsonEscape(reason) + "\"}", now);
        return lockDay(day.serviceDate()).orElseThrow();
    }

    public LocalDate serviceDate(UUID dispatchPlanId) {
        return jdbcTemplate.queryForObject(
                "SELECT service_date FROM dispatch_plan WHERE id = ?",
                LocalDate.class,
                dispatchPlanId);
    }

    public boolean hasCarriedForwardSuccessor(UUID packageId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM delivery_package
                WHERE carried_forward_from_package_id = ?
                """, Integer.class, packageId);
        return count != null && count > 0;
    }

    public CarryForwardIds insertCarryForward(
            PackageForUpdate source,
            ConfirmedLocation pickup,
            LocalDate targetDate,
            SettingsSnapshot settings,
            int currentCapacity,
            UUID actorAccountId,
            Instant now) {
        UUID requestId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        int projected = Math.addExact(currentCapacity, 1);
        jdbcTemplate.update("""
                INSERT INTO delivery_request (
                    id, owner_account_id, requested_date,
                    pickup_label, pickup_query, pickup_latitude, pickup_longitude,
                    capacity_limit_snapshot, capacity_current_snapshot,
                    capacity_requested_snapshot, capacity_projected_snapshot,
                    capacity_overflow_warning, capacity_overflow_accepted,
                    status, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, FALSE, FALSE, 'PENDING', 0, ?, ?)
                """,
                requestId, source.ownerAccountId(), Date.valueOf(targetDate),
                pickup.label(), pickup.query(), pickup.latitude(), pickup.longitude(),
                settings.dailyPackageLimit(), currentCapacity, projected,
                timestamp(now), timestamp(now));
        jdbcTemplate.update("""
                INSERT INTO delivery_package (
                    id, delivery_request_id, sequence_number,
                    recipient_name, faritra,
                    destination_label, destination_query,
                    destination_latitude, destination_longitude,
                    contact, amount_to_collect_ariary, mobile_money_paid,
                    desired_delivery_time,
                    pricing_zone_id, pricing_zone_name, delivery_fee_ariary,
                    distance_from_center_meters, pricing_distance_mode, pricing_zone_max_distance_meters,
                    pricing_policy_version, pricing_policy_id,
                    base_delivery_fee_ariary, weight_surcharge_ariary, time_surcharge_ariary,
                    effective_weight_kg, weight_source,
                    status, unassigned_reason,
                    final_outcome, actual_collected_ariary, outcome_note,
                    outcome_recorded_at, outcome_recorded_by_account_id,
                    return_completed_at, return_completed_by_account_id,
                    carried_forward_from_package_id, dispatch_plan_id, priority_at,
                    version, created_at, updated_at
                ) VALUES (
                    ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'PENDING', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                    ?, NULL, ?, 0, ?, ?
                )
                """,
                packageId, requestId,
                source.recipientName(), source.faritra(),
                source.destination().label(), source.destination().query(),
                source.destination().latitude(), source.destination().longitude(),
                source.contact(), source.amountToCollectAriary(), source.mobileMoneyPaid(),
                source.desiredDeliveryTime(),
                source.pricingZoneId(), source.pricingZoneName(), source.deliveryFeeAriary(),
                source.distanceFromCenterMeters(),
                source.pricingDistanceMode() == null ? null : source.pricingDistanceMode().name(),
                source.pricingZoneMaxDistanceMeters(),
                source.pricingPolicyVersion(), source.pricingPolicyId(),
                source.baseDeliveryFeeAriary(), source.weightSurchargeAriary(),
                source.timeSurchargeAriary(),
                source.effectiveWeightKg(), source.weightSource().name(),
                source.packageId(), timestamp(source.priorityAt()), timestamp(now), timestamp(now));
        jdbcTemplate.update("""
                INSERT INTO delivery_event (
                    id, delivery_request_id, delivery_package_id, event_type,
                    from_status, to_status, actor_account_id, payload_json, created_at
                ) VALUES (?, ?, ?, 'DELIVERY_REQUEST_CARRIED_FORWARD', NULL, 'PENDING', ?, ?, ?)
                """, UUID.randomUUID(), requestId, packageId, actorAccountId,
                "{\"sourcePackageId\":\"" + source.packageId() + "\"}", timestamp(now));
        insertDeliveryEvent(
                source, "PACKAGE_CARRIED_FORWARD", "POSTPONED", "POSTPONED", actorAccountId,
                "{\"newPackageId\":\"" + packageId + "\",\"targetDate\":\"" + targetDate + "\"}", now);
        return new CarryForwardIds(requestId, packageId, targetDate);
    }

    public void insertDayEvent(
            LocalDate serviceDate,
            String eventType,
            UUID actorAccountId,
            String payloadJson,
            Instant now) {
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_day_event (
                    id, service_date, event_type, actor_account_id, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), Date.valueOf(serviceDate), eventType,
                actorAccountId, payloadJson, timestamp(now));
    }

    private void snapshotClients(LocalDate date, int revision, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO delivery_operation_client_snapshot (
                    service_date, closure_revision, account_id, display_name, phone, created_at
                )
                SELECT ?, ?, account.id, account.display_name, account.phone, ?
                FROM app_account account
                WHERE EXISTS (
                    SELECT 1
                    FROM delivery_request request
                    JOIN delivery_package package ON package.delivery_request_id = request.id
                    WHERE request.owner_account_id = account.id
                      AND (package.dispatch_plan_id IN (
                               SELECT plan.id FROM dispatch_plan plan
                               WHERE plan.service_date = ?
                                 AND plan.status IN ('READY', 'READY_WITH_WARNINGS')
                                 AND plan.lifecycle_status = 'ACTIVE'
                           )
                           OR (request.requested_date = ? AND package.final_outcome = 'CANCELLED')
                           OR (request.requested_date = ? AND request.request_kind = 'EXPRESS'
                               AND request.express_status IN ('CONFIRMED', 'COMPLETED')))
                      AND package.cancelled_before_processing_at IS NULL
                )
                """, Date.valueOf(date), revision, timestamp(now),
                Date.valueOf(date), Date.valueOf(date), Date.valueOf(date));
    }

    private void insertDeliveryEvent(
            PackageForUpdate source,
            String eventType,
            String fromStatus,
            String toStatus,
            UUID actorAccountId,
            String payload,
            Instant now) {
        jdbcTemplate.update("""
                INSERT INTO delivery_event (
                    id, delivery_request_id, delivery_package_id, event_type,
                    from_status, to_status, actor_account_id, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), source.deliveryRequestId(), source.packageId(), eventType,
                fromStatus, toStatus, actorAccountId, payload, timestamp(now));
    }

    private OperationDay mapDay(ResultSet resultSet) throws SQLException {
        return new OperationDay(
                resultSet.getObject("service_date", LocalDate.class),
                OperationDayStatus.valueOf(resultSet.getString("status")),
                instant(resultSet, "admissions_closed_at"),
                resultSet.getObject("dispatch_plan_id", UUID.class),
                resultSet.getInt("closure_revision"),
                instant(resultSet, "closed_at"),
                resultSet.getObject("closed_by_account_id", UUID.class),
                instant(resultSet, "reopened_at"),
                resultSet.getObject("reopened_by_account_id", UUID.class),
                resultSet.getString("reopen_reason"),
                resultSet.getLong("version"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private DailyPackageRecord mapDailyPackage(ResultSet resultSet, int rowNumber) throws SQLException {
        String finalOutcome = resultSet.getString("final_outcome");
        return new DailyPackageRecord(
                resultSet.getObject("package_id", UUID.class),
                resultSet.getObject("delivery_request_id", UUID.class),
                resultSet.getObject("owner_account_id", UUID.class),
                resultSet.getString("owner_display_name"),
                resultSet.getString("owner_phone"),
                DeliveryRequestKind.valueOf(resultSet.getString("request_kind")),
                resultSet.getObject("requested_date", LocalDate.class),
                location(resultSet, "pickup"),
                resultSet.getInt("sequence_number"),
                resultSet.getString("recipient_name"),
                resultSet.getBoolean("faritra"),
                location(resultSet, "destination"),
                resultSet.getString("contact"),
                resultSet.getLong("amount_to_collect_ariary"),
                resultSet.getBoolean("mobile_money_paid"),
                resultSet.getObject("pricing_zone_id", UUID.class),
                resultSet.getString("pricing_zone_name"),
                nullableLong(resultSet, "delivery_fee_ariary"),
                nullableLong(resultSet, "distance_from_center_meters"),
                pricingDistanceMode(resultSet),
                nullableLong(resultSet, "final_delivery_fee_ariary"),
                resultSet.getString("delivery_fee_override_reason"),
                instant(resultSet, "delivery_fee_overridden_at"),
                resultSet.getLong("delivery_fee_version"),
                DeliveryPackageStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("unassigned_reason"),
                finalOutcome == null ? null : DeliveryPackageOutcome.valueOf(finalOutcome),
                nullableLong(resultSet, "actual_collected_ariary"),
                resultSet.getString("outcome_note"),
                instant(resultSet, "outcome_recorded_at"),
                instant(resultSet, "return_completed_at"),
                resultSet.getObject("carried_forward_from_package_id", UUID.class),
                resultSet.getObject("carried_forward_to_package_id", UUID.class),
                resultSet.getObject("dispatch_plan_id", UUID.class),
                resultSet.getObject("distributor_courier_id", UUID.class),
                resultSet.getString("distributor_courier_name"),
                resultSet.getObject("actual_delivery_courier_id", UUID.class),
                resultSet.getString("actual_delivery_courier_name"),
                resultSet.getString("actual_delivery_courier_contact"),
                resultSet.getString("actual_delivery_vehicle_type"),
                instant(resultSet, "estimated_delivery_at"),
                instant(resultSet, "priority_at"),
                resultSet.getLong("version"));
    }

    private PackageForUpdate mapPackageForUpdate(ResultSet resultSet) throws SQLException {
        String finalOutcome = resultSet.getString("final_outcome");
        return new PackageForUpdate(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("delivery_request_id", UUID.class),
                resultSet.getObject("owner_account_id", UUID.class),
                resultSet.getObject("requested_date", LocalDate.class),
                DeliveryRequestKind.valueOf(resultSet.getString("request_kind")),
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
                resultSet.getObject("desired_delivery_time", LocalTime.class),
                resultSet.getObject("pricing_zone_id", UUID.class),
                resultSet.getString("pricing_zone_name"),
                nullableLong(resultSet, "delivery_fee_ariary"),
                coalesce(
                        nullableLong(resultSet, "final_delivery_fee_ariary"),
                        nullableLong(resultSet, "delivery_fee_ariary")),
                resultSet.getString("delivery_fee_override_reason"),
                instant(resultSet, "delivery_fee_overridden_at"),
                resultSet.getLong("delivery_fee_version"),
                nullableLong(resultSet, "distance_from_center_meters"),
                pricingDistanceMode(resultSet),
                nullableLong(resultSet, "pricing_zone_max_distance_meters"),
                nullableLong(resultSet, "pricing_policy_version"),
                resultSet.getObject("pricing_policy_id", Short.class),
                nullableLong(resultSet, "base_delivery_fee_ariary"),
                nullableLong(resultSet, "weight_surcharge_ariary"),
                nullableLong(resultSet, "time_surcharge_ariary"),
                resultSet.getBigDecimal("effective_weight_kg"),
                PackageWeightSource.valueOf(resultSet.getString("weight_source")),
                DeliveryPackageStatus.valueOf(resultSet.getString("status")),
                finalOutcome == null ? null : DeliveryPackageOutcome.valueOf(finalOutcome),
                nullableLong(resultSet, "actual_collected_ariary"),
                resultSet.getObject("actual_delivery_courier_id", UUID.class),
                resultSet.getString("actual_delivery_courier_name"),
                resultSet.getString("actual_delivery_courier_contact"),
                resultSet.getString("actual_delivery_vehicle_type"),
                instant(resultSet, "return_completed_at"),
                resultSet.getObject("dispatch_plan_id", UUID.class),
                instant(resultSet, "priority_at"),
                resultSet.getLong("version"));
    }

    private ConfirmedLocation location(ResultSet resultSet, String prefix) throws SQLException {
        return new ConfirmedLocation(
                resultSet.getString(prefix + "_label"),
                resultSet.getString(prefix + "_query"),
                resultSet.getDouble(prefix + "_latitude"),
                resultSet.getDouble(prefix + "_longitude"));
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private PricingDistanceMode pricingDistanceMode(ResultSet resultSet) throws SQLException {
        String value = resultSet.getString("pricing_distance_mode");
        return value == null ? null : PricingDistanceMode.valueOf(value);
    }

    private Long coalesce(Long preferred, Long fallback) {
        return preferred == null ? fallback : preferred;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String jsonString(String value) {
        return value == null ? "null" : "\"" + jsonEscape(value) + "\"";
    }

    public record SettingsSnapshot(int dailyPackageLimit, BigDecimal defaultPackageWeightKg) {
    }

    public record CarryForwardIds(UUID requestId, UUID packageId, LocalDate requestedDate) {
    }

    public record CourierSnapshot(UUID courierId, String name, String contact, String vehicleType) {
    }

    public record DeliveryFeeRecord(
            UUID packageId,
            Long calculatedFeeAriary,
            Long finalFeeAriary,
            String overrideReason,
            Instant overriddenAt,
            UUID overriddenByAccountId,
            long feeVersion,
            long version) {
    }

    public record PackageForUpdate(
            UUID packageId,
            UUID deliveryRequestId,
            UUID ownerAccountId,
            LocalDate requestedDate,
            DeliveryRequestKind requestKind,
            int sequenceNumber,
            String recipientName,
            boolean faritra,
            ConfirmedLocation destination,
            String contact,
            long amountToCollectAriary,
            boolean mobileMoneyPaid,
            LocalTime desiredDeliveryTime,
            UUID pricingZoneId,
            String pricingZoneName,
            Long deliveryFeeAriary,
            Long finalDeliveryFeeAriary,
            String deliveryFeeOverrideReason,
            Instant deliveryFeeOverriddenAt,
            long deliveryFeeVersion,
            Long distanceFromCenterMeters,
            PricingDistanceMode pricingDistanceMode,
            Long pricingZoneMaxDistanceMeters,
            Long pricingPolicyVersion,
            Short pricingPolicyId,
            Long baseDeliveryFeeAriary,
            Long weightSurchargeAriary,
            Long timeSurchargeAriary,
            BigDecimal effectiveWeightKg,
            PackageWeightSource weightSource,
            DeliveryPackageStatus technicalStatus,
            DeliveryPackageOutcome finalOutcome,
            Long actualCollectedAriary,
            UUID actualCourierId,
            String actualCourierName,
            String actualCourierContact,
            String actualCourierVehicleType,
            Instant returnCompletedAt,
            UUID dispatchPlanId,
            Instant priorityAt,
            long version) {
    }
}
