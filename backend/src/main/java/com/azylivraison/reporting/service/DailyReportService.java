package com.azylivraison.reporting.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.azylivraison.dailyworkflow.domain.DailyPackageRecord;
import com.azylivraison.dailyworkflow.domain.OperationDay;
import com.azylivraison.dailyworkflow.domain.OperationDayStatus;
import com.azylivraison.dailyworkflow.service.DailyWorkflowConflictException;
import com.azylivraison.dailyworkflow.service.DailyWorkflowNotFoundException;
import com.azylivraison.dailyworkflow.repository.ClientClosureRepository;
import com.azylivraison.dailyworkflow.domain.ClientClosureModels.ClientClosure;
import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.reporting.domain.ClientDailyReport;
import com.azylivraison.reporting.domain.DailyReport;
import com.azylivraison.reporting.domain.DailyReport.ClientSummary;
import com.azylivraison.reporting.domain.DailyReport.Closure;
import com.azylivraison.reporting.domain.DailyReport.CourierSummary;
import com.azylivraison.reporting.domain.DailyReport.PackageLine;
import com.azylivraison.reporting.domain.DailyReport.PackageOutcome;
import com.azylivraison.reporting.domain.DailyReport.Totals;
import com.azylivraison.reporting.render.DailyReportPdfRenderer;
import com.azylivraison.reporting.render.DailyReportXlsxRenderer;
import com.azylivraison.reporting.repository.DailyReportRepository;
import com.azylivraison.reporting.repository.DailyReportRepository.ClientIdentity;
import com.azylivraison.reporting.repository.DailyReportRepository.ReportSource;
import com.azylivraison.reporting.repository.DailyReportRepository.SuccessorDetails;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Builds one consistent report snapshot and delegates only presentation to the renderers. */
@Service
public class DailyReportService {

    private final DailyReportRepository repository;
    private final DailyReportPdfRenderer pdfRenderer;
    private final DailyReportXlsxRenderer xlsxRenderer;
    private final ClientClosureRepository clientClosures;

    public DailyReportService(
            DailyReportRepository repository,
            DailyReportPdfRenderer pdfRenderer,
            DailyReportXlsxRenderer xlsxRenderer,
            ClientClosureRepository clientClosures) {
        this.repository = repository;
        this.pdfRenderer = pdfRenderer;
        this.xlsxRenderer = xlsxRenderer;
        this.clientClosures = clientClosures;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] renderGeneralPdf(LocalDate serviceDate) {
        return pdfRenderer.render(build(serviceDate));
    }

    /**
     * Returns the same immutable closed-day view used by every report renderer.
     * Financial settlement materialization uses this entry point so that a
     * client's payable amount cannot drift away from the report shown to them.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DailyReport getGeneralReport(LocalDate serviceDate) {
        return build(serviceDate);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] renderGeneralXlsx(LocalDate serviceDate) {
        return xlsxRenderer.render(build(serviceDate));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] renderClientPdf(LocalDate serviceDate, UUID accountId) {
        DailyReport report = clientSnapshot(serviceDate, accountId).report();
        return pdfRenderer.renderForClient(report, accountId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] renderClientXlsx(LocalDate serviceDate, UUID accountId) {
        DailyReport report = clientSnapshot(serviceDate, accountId).report();
        return xlsxRenderer.renderForClient(report, accountId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ClientReportSummary> listClientReports(UUID accountId) {
        return listClientReportSnapshots(accountId).stream()
                .map(report -> new ClientReportSummary(
                        report.reportId(),
                        report.serviceDate(),
                        report.totals()))
                .toList();
    }

    /** Internal closed-report metadata used to materialize immutable settlements. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ClientReportSnapshot> listClientReportSnapshots(UUID accountId) {
        if (accountId == null) throw new IllegalArgumentException("accountId");
        return repository.listClosedDatesForClient(accountId).stream()
                .map(date -> clientSnapshot(date, accountId))
                .map(snapshot -> new ClientReportSnapshot(
                        snapshot.reportId(), snapshot.report().serviceDate(), snapshot.revision(),
                        snapshot.closedAt(), snapshot.report().totals()))
                .toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ClientDailyReport getClientReport(LocalDate serviceDate, UUID accountId) {
        ClientReportMaterialization snapshot = clientSnapshot(serviceDate, accountId);
        return clientApiView(snapshot.report(), accountId, snapshot.reportId());
    }

    public static UUID deriveClientReportId(UUID generalReportId, UUID clientAccountId) {
        if (generalReportId == null) throw new IllegalArgumentException("generalReportId");
        if (clientAccountId == null) throw new IllegalArgumentException("clientAccountId");
        return UUID.nameUUIDFromBytes(
                (generalReportId + "|" + clientAccountId).getBytes(StandardCharsets.UTF_8));
    }

    private DailyReport build(LocalDate serviceDate) {
        if (serviceDate == null) throw new IllegalArgumentException("serviceDate");
        OperationDay day = repository.findDay(serviceDate)
                .orElseThrow(() -> new DailyWorkflowNotFoundException(
                        "Aucune journée de livraison n'existe pour cette date."));
        if (day.status() != OperationDayStatus.CLOSED
                || day.closedAt() == null
                || day.closedByAccountId() == null) {
            throw new DailyWorkflowConflictException(
                    "Clôturez la journée avant de générer ses rapports.");
        }
        return build(day, null, null);
    }

    private ClientReportMaterialization clientSnapshot(LocalDate serviceDate, UUID accountId) {
        if (serviceDate == null) throw new IllegalArgumentException("serviceDate");
        if (accountId == null) throw new IllegalArgumentException("accountId");
        OperationDay day = repository.findDay(serviceDate)
                .orElseThrow(() -> new DailyWorkflowNotFoundException(
                        "Aucune journée de livraison n'existe pour cette date."));
        var closureState = clientClosures.findClosure(serviceDate, accountId);
        if (closureState.isPresent()) {
            ClientClosure closure = closureState.get();
            if (!closure.closed()) {
                throw new DailyWorkflowConflictException(
                        "Clôturez ce client avant de générer son rapport.");
            }
            OperationDay reportDay = new OperationDay(
                    day.serviceDate(), OperationDayStatus.CLOSED, day.admissionsClosedAt(),
                    day.dispatchPlanId(), closure.closureRevision(), closure.closedAt(),
                    closure.closedByAccountId(), closure.reopenedAt(), closure.reopenedByAccountId(),
                    closure.reopenReason(), closure.version(), day.createdAt(), day.updatedAt());
            DailyReport report = build(reportDay, accountId, closure.reportId());
            return new ClientReportMaterialization(
                    closure.reportId(), closure.closureRevision(), closure.closedAt(), report);
        }
        if (day.status() != OperationDayStatus.CLOSED
                || day.closedAt() == null || day.closedByAccountId() == null) {
            throw new DailyWorkflowConflictException(
                    "Clôturez ce client avant de générer son rapport.");
        }
        DailyReport report = clientReportView(build(day, null, null), accountId);
        return new ClientReportMaterialization(
                deriveClientReportId(report.reportId(), accountId), day.closureRevision(),
                day.closedAt(), report);
    }

    private DailyReport build(OperationDay day, UUID clientAccountId, UUID reportIdOverride) {
        ReportSource source = repository.load(day, clientAccountId);
        requireFinalOutcomes(source.packages());
        List<PackageLine> packageLines = source.packages().stream()
                .map(deliveryPackage -> packageLine(deliveryPackage, source.successors()))
                .toList();
        List<ClientSummary> clients = clientSummaries(source);
        List<CourierSummary> couriers = courierSummaries(source);
        StringBuilder reportIdentity = new StringBuilder()
                .append(day.serviceDate()).append('|')
                .append(day.closureRevision()).append('|')
                .append(day.dispatchPlanId());
        for (DailyPackageRecord deliveryPackage : source.packages()) {
            SuccessorDetails successor = source.successors().get(deliveryPackage.packageId());
            // A deferred rescheduling adds logistical information after closure,
            // but must not change a report ID already used by financial settlements.
            UUID successorAtClosure = successor != null && !successor.createdAt().isAfter(day.closedAt())
                    ? successor.packageId() : null;
            reportIdentity.append('|').append(deliveryPackage.packageId())
                    .append(':').append(deliveryPackage.finalOutcome())
                    .append(':').append(deliveryPackage.actualCollectedAriary())
                    .append(':').append(deliveryPackage.actualCourierId())
                    .append(':').append(deliveryPackage.chargedDeliveryFeeAriary())
                    .append(':').append(deliveryPackage.returnCompletedAt())
                    .append(':').append(successorAtClosure);
        }
        String closedBy = firstNonBlank(
                source.context().closedByDisplayName(),
                source.context().closedByUsername(),
                day.closedByAccountId().toString());
        UUID reportId = reportIdOverride == null
                ? UUID.nameUUIDFromBytes(reportIdentity.toString().getBytes(StandardCharsets.UTF_8))
                : reportIdOverride;
        DailyReport report = new DailyReport(
                reportId,
                day.serviceDate(),
                day.dispatchPlanId(),
                source.context().hubLabel(),
                source.context().sourcePlanCount(),
                day.closedAt(),
                new Closure(day.closedAt(), day.closedByAccountId(), closedBy, day.closureRevision()),
                totals(source.packages()),
                clients,
                couriers,
                packageLines);
        if (clientAccountId != null) requireClient(report, clientAccountId);
        return report;
    }

    private void requireFinalOutcomes(List<DailyPackageRecord> packages) {
        long incomplete = packages.stream().filter(deliveryPackage -> !deliveryPackage.finalState()).count();
        if (incomplete > 0) {
            throw new DailyWorkflowConflictException(
                    "La journée clôturée contient " + incomplete + " colis sans état final.");
        }
    }

    private List<ClientSummary> clientSummaries(ReportSource source) {
        Map<UUID, List<DailyPackageRecord>> byClient = groupByOwner(source.packages());
        List<ClientSummary> clients = new ArrayList<>();
        for (Map.Entry<UUID, List<DailyPackageRecord>> entry : byClient.entrySet()) {
            DailyPackageRecord first = entry.getValue().getFirst();
            ClientIdentity identity = source.clientIdentities().get(entry.getKey());
            clients.add(new ClientSummary(
                    entry.getKey(),
                    first.ownerDisplayName(),
                    identity == null ? entry.getKey().toString() : identity.username(),
                    first.ownerPhone(),
                    totals(entry.getValue())));
        }
        return List.copyOf(clients);
    }

    private List<CourierSummary> courierSummaries(ReportSource source) {
        Map<UUID, List<DailyPackageRecord>> byCourier = new LinkedHashMap<>();
        for (DailyPackageRecord deliveryPackage : source.packages()) {
            if (deliveryPackage.actualCourierId() != null) {
                byCourier.computeIfAbsent(deliveryPackage.actualCourierId(), ignored -> new ArrayList<>())
                        .add(deliveryPackage);
            }
        }

        List<CourierSummary> couriers = new ArrayList<>();
        for (Map.Entry<UUID, List<DailyPackageRecord>> entry : byCourier.entrySet()) {
            List<DailyPackageRecord> packages = entry.getValue();
            DailyPackageRecord first = packages.getFirst();
            couriers.add(new CourierSummary(
                    entry.getKey(),
                    first.actualCourierName(),
                    first.actualCourierContact(),
                    vehicleLabel(first.actualCourierVehicleType()),
                    totals(packages)));
        }
        return List.copyOf(couriers);
    }

    private Map<UUID, List<DailyPackageRecord>> groupByOwner(List<DailyPackageRecord> packages) {
        Map<UUID, List<DailyPackageRecord>> byOwner = new LinkedHashMap<>();
        for (DailyPackageRecord deliveryPackage : packages) {
            byOwner.computeIfAbsent(deliveryPackage.ownerAccountId(), ignored -> new ArrayList<>())
                    .add(deliveryPackage);
        }
        return byOwner;
    }

    private PackageLine packageLine(
            DailyPackageRecord deliveryPackage,
            Map<UUID, SuccessorDetails> successors) {
        SuccessorDetails successor = successors.get(deliveryPackage.packageId());
        return new PackageLine(
                deliveryPackage.packageId(),
                deliveryPackage.deliveryRequestId(),
                deliveryPackage.sequenceNumber(),
                deliveryPackage.ownerAccountId(),
                deliveryPackage.ownerDisplayName(),
                deliveryPackage.recipientName(),
                deliveryPackage.contact(),
                deliveryPackage.pickup().label(),
                deliveryPackage.destination().label(),
                deliveryPackage.pricingZoneName(),
                PackageOutcome.valueOf(deliveryPackage.finalOutcome().name()),
                deliveryPackage.distributorCourierId(),
                deliveryPackage.distributorCourierName(),
                deliveryPackage.actualCourierId(),
                deliveryPackage.actualCourierName(),
                deliveryPackage.amountToCollectAriary(),
                deliveryPackage.recoveredAriary(),
                deliveryPackage.chargedDeliveryFeeAriary(),
                deliveryPackage.amountToRemitAriary(),
                deliveryPackage.mobileMoneyPaid(),
                deliveryPackage.faritra(),
                deliveryPackage.outcomeRecordedAt(),
                deliveryPackage.outcomeNote(),
                deliveryPackage.returnCompletedAt() != null,
                deliveryPackage.returnCompletedAt(),
                successor == null ? deliveryPackage.carriedForwardToPackageId() : successor.packageId(),
                successor == null ? null : successor.requestedDate(),
                successor == null ? null : successor.pickupLabel());
    }

    private Totals totals(List<DailyPackageRecord> packages) {
        int delivered = 0;
        int cancelled = 0;
        int postponed = 0;
        int returnToSender = 0;
        int returned = 0;
        long recovered = 0;
        long fees = 0;
        for (DailyPackageRecord deliveryPackage : packages) {
            DeliveryPackageOutcome outcome = deliveryPackage.finalOutcome();
            if (outcome != null) {
                switch (outcome) {
                    case DELIVERED -> delivered++;
                    case CANCELLED -> cancelled++;
                    case POSTPONED -> postponed++;
                    case RETURN_TO_SENDER -> {
                        returnToSender++;
                        if (deliveryPackage.returnCompletedAt() != null) returned++;
                    }
                }
            }
            // Mobile Money is intentionally not consulted here.
            recovered = Math.addExact(recovered, deliveryPackage.recoveredAriary());
            fees = Math.addExact(fees, deliveryPackage.chargedDeliveryFeeAriary());
        }
        return new Totals(
                packages.size(), delivered, cancelled, postponed, returnToSender, returned,
                recovered, fees, Math.subtractExact(recovered, fees));
    }

    private void requireClient(DailyReport report, UUID accountId) {
        if (accountId == null || report.clients().stream().noneMatch(client -> client.accountId().equals(accountId))) {
            throw new DailyWorkflowNotFoundException("Ce compte client n'a aucun colis dans cette journée.");
        }
    }

    private DailyReport clientReportView(DailyReport report, UUID accountId) {
        requireClient(report, accountId);
        ClientSummary client = report.clients().stream()
                .filter(item -> item.accountId().equals(accountId))
                .findFirst()
                .orElseThrow();
        List<PackageLine> packages = report.packages().stream()
                .filter(item -> item.ownerAccountId().equals(accountId))
                .toList();
        return new DailyReport(
                report.reportId(),
                report.serviceDate(),
                report.dispatchPlanId(),
                report.hubLabel(),
                report.sourcePlanCount(),
                report.generatedAt(),
                report.closure(),
                client.totals(),
                List.of(client),
                List.of(),
                packages);
    }

    private ClientDailyReport clientApiView(
            DailyReport report, UUID accountId, UUID clientReportId) {
        ClientSummary client = report.clients().stream()
                .filter(item -> item.accountId().equals(accountId))
                .findFirst()
                .orElseThrow();
        boolean showRequestKind = repository.clientExpressEnabled(accountId);
        Map<UUID, com.azylivraison.delivery.domain.DeliveryRequestKind> requestKinds =
                repository.requestKinds(report.packages().stream().map(PackageLine::packageId)
                        .collect(java.util.stream.Collectors.toSet()));
        return new ClientDailyReport(
                clientReportId,
                report.serviceDate(),
                client.displayName(),
                client.phone(),
                report.totals(),
                report.packages().stream()
                        .map(item -> new ClientDailyReport.PackageLine(
                                item.packageId(),
                                item.recipientName(),
                                item.recipientContact(),
                                item.pickupLabel(),
                                item.destinationLabel(),
                                showRequestKind ? requestKinds.get(item.packageId()) : null,
                                item.outcome(),
                                item.outcomeNote(),
                                item.actualAmountCollectedAriary(),
                                item.appliedDeliveryFeeAriary(),
                                item.amountToRemitAriary(),
                                item.mobileMoneyPaid(),
                                item.returnedToSender()))
                        .toList());
    }

    public record ClientReportSummary(
            UUID reportId,
            LocalDate serviceDate,
            Totals totals) {
    }

    public record ClientReportSnapshot(
            UUID reportId,
            LocalDate serviceDate,
            int revision,
            Instant closedAt,
            Totals totals) {
    }

    private record ClientReportMaterialization(
            UUID reportId,
            int revision,
            Instant closedAt,
            DailyReport report) {
    }

    private String vehicleLabel(String vehicleType) {
        if (vehicleType == null) return null;
        return switch (vehicleType) {
            case "MOTO" -> "Moto";
            case "BICYCLE" -> "Bicyclette";
            default -> vehicleType;
        };
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "—";
    }
}
