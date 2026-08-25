package com.azylivraison.dispatch.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.azylivraison.dispatch.domain.DispatchSourcePackage;
import com.azylivraison.planning.domain.Location;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DispatchSourceRepository {

    private static final String SELECT_PENDING = """
            SELECT p.id AS package_id,
                   p.delivery_request_id,
                   dr.owner_account_id,
                   a.display_name AS service_client_name,
                   dr.requested_date,
                   dr.pickup_label, dr.pickup_query,
                   dr.pickup_latitude, dr.pickup_longitude,
                   p.recipient_name, p.faritra,
                   p.destination_label, p.destination_query,
                   p.destination_latitude, p.destination_longitude,
                   p.contact, p.amount_to_collect_ariary, p.mobile_money_paid,
                   p.effective_weight_kg
            FROM delivery_package p
            JOIN delivery_request dr ON dr.id = p.delivery_request_id
            JOIN app_account a ON a.id = dr.owner_account_id
            LEFT JOIN dispatch_package_reservation reservation
                   ON reservation.delivery_package_id = p.id
            WHERE p.status = 'PENDING'
              AND dr.requested_date <= :serviceDate
              AND reservation.delivery_package_id IS NULL
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DispatchSourceRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DispatchSourcePackage> findPending(
            LocalDate serviceDate,
            List<UUID> requestedPackageIds) {
        String packageFilter = requestedPackageIds == null || requestedPackageIds.isEmpty()
                ? ""
                : " AND p.id IN (:packageIds)";
        MapSqlParameterSource parameters = new MapSqlParameterSource("serviceDate", serviceDate);
        if (!packageFilter.isEmpty()) {
            parameters.addValue("packageIds", requestedPackageIds);
        }
        return jdbcTemplate.query(
                SELECT_PENDING + packageFilter + " ORDER BY dr.requested_date, dr.created_at, p.sequence_number, p.id",
                parameters,
                (resultSet, rowNumber) -> map(resultSet));
    }

    public void markAssigned(UUID dispatchPlanId, List<UUID> packageIds) {
        if (packageIds.isEmpty()) return;
        jdbcTemplate.update("""
                UPDATE delivery_package
                SET status = 'DISPATCHED',
                    unassigned_reason = NULL,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id IN (:packageIds)
                  AND EXISTS (
                      SELECT 1 FROM dispatch_package_reservation reservation
                      WHERE reservation.delivery_package_id = delivery_package.id
                        AND reservation.dispatch_plan_id = :dispatchPlanId
                  )
                """, Map.of("packageIds", packageIds, "dispatchPlanId", dispatchPlanId));
    }

    public void markUnassigned(UUID dispatchPlanId, Map<UUID, String> reasons) {
        for (Map.Entry<UUID, String> reason : reasons.entrySet()) {
            jdbcTemplate.update("""
                    UPDATE delivery_package
                    SET status = 'PENDING',
                        unassigned_reason = :reason,
                        version = version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :packageId
                      AND EXISTS (
                          SELECT 1 FROM dispatch_package_reservation reservation
                          WHERE reservation.delivery_package_id = delivery_package.id
                            AND reservation.dispatch_plan_id = :dispatchPlanId
                      )
                    """, Map.of(
                            "reason", reason.getValue(),
                            "packageId", reason.getKey(),
                            "dispatchPlanId", dispatchPlanId));
        }
    }

    public void recomputeRequestStatuses(List<UUID> requestIds) {
        for (UUID requestId : requestIds.stream().distinct().toList()) {
            jdbcTemplate.update("""
                    UPDATE delivery_request
                    SET status = CASE
                            WHEN NOT EXISTS (
                                SELECT 1 FROM delivery_package p
                                WHERE p.delivery_request_id = delivery_request.id
                                  AND p.status <> 'DISPATCHED'
                            ) THEN 'DISPATCHED'
                            WHEN EXISTS (
                                SELECT 1 FROM delivery_package p
                                WHERE p.delivery_request_id = delivery_request.id
                                  AND p.status = 'DISPATCHED'
                            ) THEN 'PARTIALLY_DISPATCHED'
                            ELSE 'PENDING'
                        END,
                        version = version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :requestId
                    """, Map.of("requestId", requestId));
        }
    }

    private DispatchSourcePackage map(ResultSet resultSet) throws SQLException {
        return new DispatchSourcePackage(
                resultSet.getObject("package_id", UUID.class),
                resultSet.getObject("delivery_request_id", UUID.class),
                resultSet.getObject("owner_account_id", UUID.class),
                resultSet.getString("service_client_name"),
                resultSet.getObject("requested_date", LocalDate.class),
                new Location(
                        resultSet.getString("pickup_label"),
                        resultSet.getString("pickup_query"),
                        resultSet.getDouble("pickup_latitude"),
                        resultSet.getDouble("pickup_longitude")),
                resultSet.getString("recipient_name"),
                resultSet.getBoolean("faritra"),
                new Location(
                        resultSet.getString("destination_label"),
                        resultSet.getString("destination_query"),
                        resultSet.getDouble("destination_latitude"),
                        resultSet.getDouble("destination_longitude")),
                resultSet.getString("contact"),
                resultSet.getLong("amount_to_collect_ariary"),
                resultSet.getBoolean("mobile_money_paid"),
                resultSet.getBigDecimal("effective_weight_kg"));
    }
}
