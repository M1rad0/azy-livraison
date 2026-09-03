package com.azylivraison.reporting.repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.azylivraison.dailyworkflow.domain.DailyPackageRecord;
import com.azylivraison.dailyworkflow.domain.OperationDay;
import com.azylivraison.dailyworkflow.repository.DailyWorkflowRepository;
import com.azylivraison.delivery.domain.DeliveryRequestKind;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the immutable operational snapshot required to render a closed day. */
@Repository
public class DailyReportRepository {

    private final DailyWorkflowRepository workflowRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DailyReportRepository(
            DailyWorkflowRepository workflowRepository,
            NamedParameterJdbcTemplate jdbcTemplate) {
        this.workflowRepository = workflowRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OperationDay> findDay(LocalDate serviceDate) {
        return workflowRepository.findDay(serviceDate);
    }

    public List<LocalDate> listClosedDatesForClient(UUID accountId) {
        return jdbcTemplate.query("""
                SELECT operation_day.service_date
                FROM delivery_operation_day operation_day
                JOIN delivery_operation_client_snapshot snapshot
                  ON snapshot.service_date = operation_day.service_date
                 AND snapshot.closure_revision = operation_day.closure_revision
                WHERE operation_day.status = 'CLOSED'
                  AND snapshot.account_id = :accountId
                ORDER BY operation_day.service_date DESC
                """, Map.of("accountId", accountId),
                (resultSet, rowNumber) -> resultSet.getObject("service_date", LocalDate.class));
    }

    public ReportSource load(OperationDay day) {
        List<DailyPackageRecord> packages = workflowRepository.findPackages(day);
        ReportContext context = context(day);
        Set<UUID> accountIds = packages.stream()
                .map(DailyPackageRecord::ownerAccountId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> sourcePackageIds = packages.stream()
                .filter(deliveryPackage -> deliveryPackage.carriedForwardToPackageId() != null)
                .map(DailyPackageRecord::packageId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new ReportSource(
                day,
                packages,
                context,
                clientIdentities(accountIds),
                successors(sourcePackageIds));
    }

    public boolean clientExpressEnabled(UUID accountId) {
        List<Boolean> values = jdbcTemplate.getJdbcTemplate().query(
                "SELECT express_delivery_enabled FROM client_profile WHERE account_id = ?",
                (resultSet, rowNumber) -> resultSet.getBoolean("express_delivery_enabled"), accountId);
        return values.stream().findFirst().orElse(false);
    }

    public Map<UUID, DeliveryRequestKind> requestKinds(Set<UUID> packageIds) {
        if (packageIds.isEmpty()) return Map.of();
        Map<UUID, DeliveryRequestKind> kinds = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT package.id, request.request_kind
                FROM delivery_package package
                JOIN delivery_request request ON request.id = package.delivery_request_id
                WHERE package.id IN (:packageIds)
                """, new MapSqlParameterSource("packageIds", packageIds),
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet ->
                        kinds.put(resultSet.getObject("id", UUID.class),
                                DeliveryRequestKind.valueOf(resultSet.getString("request_kind"))));
        return Map.copyOf(kinds);
    }

    private ReportContext context(OperationDay day) {
        return jdbcTemplate.query("""
                SELECT plan.hub_label,
                       closer.username AS closed_by_username,
                       closer.display_name AS closed_by_display_name,
                       (SELECT COUNT(*)
                        FROM dispatch_plan source_plan
                        WHERE source_plan.service_date = :serviceDate
                          AND source_plan.status IN ('READY', 'READY_WITH_WARNINGS')
                          AND source_plan.lifecycle_status = 'ACTIVE') AS source_plan_count
                FROM dispatch_plan plan
                JOIN app_account closer ON closer.id = :closedByAccountId
                WHERE plan.id = :dispatchPlanId
                """, Map.of(
                        "closedByAccountId", day.closedByAccountId(),
                        "dispatchPlanId", day.dispatchPlanId(),
                        "serviceDate", day.serviceDate()),
                (resultSet, rowNumber) -> new ReportContext(
                        resultSet.getString("hub_label"),
                        resultSet.getString("closed_by_username"),
                        resultSet.getString("closed_by_display_name"),
                        resultSet.getInt("source_plan_count")))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Les métadonnées du plan clôturé sont absentes."));
    }

    private Map<UUID, ClientIdentity> clientIdentities(Set<UUID> accountIds) {
        if (accountIds.isEmpty()) return Map.of();
        Map<UUID, ClientIdentity> identities = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT id, username
                FROM app_account
                WHERE id IN (:accountIds)
                """, new MapSqlParameterSource("accountIds", accountIds), resultSet -> {
                    UUID accountId = resultSet.getObject("id", UUID.class);
                    identities.put(accountId, new ClientIdentity(accountId, resultSet.getString("username")));
                });
        return Map.copyOf(identities);
    }

    private Map<UUID, SuccessorDetails> successors(Set<UUID> sourcePackageIds) {
        if (sourcePackageIds.isEmpty()) return Map.of();
        Map<UUID, SuccessorDetails> successors = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT successor.carried_forward_from_package_id AS source_package_id,
                       successor.id AS successor_package_id,
                       request.requested_date,
                       request.pickup_label
                FROM delivery_package successor
                JOIN delivery_request request ON request.id = successor.delivery_request_id
                WHERE successor.carried_forward_from_package_id IN (:sourcePackageIds)
                """, new MapSqlParameterSource("sourcePackageIds", sourcePackageIds), resultSet -> {
                    UUID sourceId = resultSet.getObject("source_package_id", UUID.class);
                    successors.put(sourceId, new SuccessorDetails(
                            resultSet.getObject("successor_package_id", UUID.class),
                            resultSet.getObject("requested_date", LocalDate.class),
                            resultSet.getString("pickup_label")));
                });
        return Map.copyOf(successors);
    }

    public record ReportSource(
            OperationDay day,
            List<DailyPackageRecord> packages,
            ReportContext context,
            Map<UUID, ClientIdentity> clientIdentities,
            Map<UUID, SuccessorDetails> successors) {

        public ReportSource {
            packages = List.copyOf(packages);
            clientIdentities = Map.copyOf(clientIdentities);
            successors = Map.copyOf(successors);
        }
    }

    public record ReportContext(
            String hubLabel,
            String closedByUsername,
            String closedByDisplayName,
            int sourcePlanCount) {
    }

    public record ClientIdentity(UUID accountId, String username) {
    }

    public record SuccessorDetails(
            UUID packageId,
            LocalDate requestedDate,
            String pickupLabel) {
    }

}
