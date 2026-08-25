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
import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
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

    public DailyReportService(
            DailyReportRepository repository,
            DailyReportPdfRenderer pdfRenderer,
            DailyReportXlsxRenderer xlsxRenderer) {
        this.repository = repository;
        this.pdfRenderer = pdfRenderer;
        this.xlsxRenderer = xlsxRenderer;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] renderGeneralPdf(LocalDate serviceDate) {
        return pdfRenderer.render(build(serviceDate));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] renderGeneralXlsx(LocalDate serviceDate) {
        return xlsxRenderer.render(build(serviceDate));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] renderClientPdf(LocalDate serviceDate, UUID accountId) {
        DailyReport report = build(serviceDate);
        requireClient(report, accountId);
        return pdfRenderer.renderForClient(report, accountId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] renderClientXlsx(LocalDate serviceDate, UUID accountId) {
        DailyReport report = build(serviceDate);
        requireClient(report, accountId);
        return xlsxRenderer.renderForClient(report, accountId);
    }

    private DailyReport build(LocalDate serviceDate) {
        if (serviceDate == null) throw new IllegalArgumentException("serviceDate");
        OperationDay day = repository.findDay(serviceDate)
                .orElseThrow(() -> new DailyWorkflowNotFoundException(
                        "Aucune journée de livraison n'existe pour cette date."));
        if (day.status() != OperationDayStatus.CLOSED
                || day.dispatchPlanId() == null
                || day.closedAt() == null
                || day.closedByAccountId() == null) {
            throw new DailyWorkflowConflictException(
                    "Clôturez la journée avant de générer ses rapports.");
        }

        ReportSource source = repository.load(day);
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
            reportIdentity.append('|').append(deliveryPackage.packageId())
                    .append(':').append(deliveryPackage.finalOutcome())
                    .append(':').append(deliveryPackage.actualCollectedAriary())
                    .append(':').append(deliveryPackage.actualCourierId())
                    .append(':').append(deliveryPackage.returnCompletedAt())
                    .append(':').append(deliveryPackage.carriedForwardToPackageId());
        }
        String closedBy = firstNonBlank(
                source.context().closedByDisplayName(),
                source.context().closedByUsername(),
                day.closedByAccountId().toString());
        return new DailyReport(
                UUID.nameUUIDFromBytes(reportIdentity.toString().getBytes(StandardCharsets.UTF_8)),
                day.serviceDate(),
                day.dispatchPlanId(),
                source.context().hubLabel(),
                source.context().sourcePlanCount(),
                Instant.now(),
                new Closure(day.closedAt(), day.closedByAccountId(), closedBy, day.closureRevision()),
                totals(source.packages()),
                clients,
                couriers,
                packageLines);
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
