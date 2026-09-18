package com.azylivraison.delivery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.azylivraison.administration.service.CourierProfileService;
import com.azylivraison.delivery.domain.ExpressFuelAdjustment;
import com.azylivraison.delivery.domain.ExpressFuelCalculation;
import com.azylivraison.delivery.domain.ExpressFuelCalculationStatus;
import com.azylivraison.delivery.domain.ExpressFuelLine;
import com.azylivraison.delivery.repository.ExpressFuelRepository;
import com.azylivraison.delivery.repository.ExpressFuelRepository.DeliveryStop;
import com.azylivraison.optimization.service.ExpressRouteOptimizer;
import com.azylivraison.routing.domain.RoutingPoint;
import com.azylivraison.routing.service.RoutingService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpressFuelService {

    private final ExpressFuelRepository repository;
    private final CourierProfileService courierProfiles;
    private final RoutingService routingService;
    private final ExpressRouteOptimizer routeOptimizer;

    public ExpressFuelService(ExpressFuelRepository repository,
            CourierProfileService courierProfiles, RoutingService routingService,
            ExpressRouteOptimizer routeOptimizer) {
        this.repository = repository;
        this.courierProfiles = courierProfiles;
        this.routingService = routingService;
        this.routeOptimizer = routeOptimizer;
    }

    @Transactional(readOnly = true)
    public ExpressFuelCalculation get(UUID requestId) {
        var request = repository.requestSnapshot(requestId)
                .orElseThrow(() -> new DeliveryRequestNotFoundException(requestId));
        return repository.find(requestId).orElseGet(() -> waiting(request));
    }

    @Transactional(readOnly = true)
    public List<ExpressFuelCalculation> forDate(LocalDate serviceDate) {
        return repository.completedRequestsForDate(serviceDate).stream()
                .map(request -> repository.find(request.requestId()).orElseGet(() -> waiting(request)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExpressFuelCalculation> forDateAndClient(
            LocalDate serviceDate, UUID clientAccountId) {
        return repository.completedRequestsForDateAndClient(serviceDate, clientAccountId).stream()
                .map(request -> repository.find(request.requestId()).orElseGet(() -> waiting(request)))
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExpressFuelCalculation recalculate(UUID requestId, Long expectedVersion) {
        return recalculateCascade(requestId, expectedVersion);
    }

    private ExpressFuelCalculation recalculateCascade(UUID requestId, Long expectedVersion) {
        var target = repository.requestSnapshot(requestId)
                .orElseThrow(() -> new DeliveryRequestNotFoundException(requestId));
        if (repository.isFinanciallyFinalized(requestId)) {
            throw new InvalidDeliveryRequestFilterException(
                    "Le carburant de cette demande est déjà figé dans les rémunérations.");
        }
        if (!target.completed()) return get(requestId);

        Map<UUID, PreviousEndpoint> previousEndpoints = new HashMap<>();
        boolean targetReached = false;
        for (var request : repository.requestsForChainDate(target.serviceDate())) {
            List<DeliveryStop> stops = repository.deliveryStops(request.requestId());
            if (!request.completed()) {
                updatePendingEndpoints(repository.involvedCourierIds(request.requestId()), previousEndpoints);
                continue;
            }

            repository.ensure(request.requestId(), request.returnToPickup());
            ExpressFuelCalculation current = repository.find(request.requestId()).orElseThrow();
            if (request.requestId().equals(requestId)) {
                targetReached = true;
                if (expectedVersion != null && current.version() != expectedVersion) {
                    throw new InvalidDeliveryRequestFilterException(
                            "Le calcul carburant a été modifié entre-temps. Rechargez la page.");
                }
            }

            // A later request can already belong to a client whose compensation was
            // finalized independently. It remains an immutable link in the route chain:
            // use its stored endpoint, but never rewrite its financial snapshot.
            if (!request.requestId().equals(requestId)
                    && repository.isFinanciallyFinalized(request.requestId())) {
                updateStoredEndpoints(request, current, stops, previousEndpoints);
                continue;
            }

            boolean explicitTargetRecalculation = request.requestId().equals(requestId)
                    && expectedVersion != null;
            if (isManual(current.status()) && !explicitTargetRecalculation) {
                List<ExpressFuelLine> reconciledLines = reconcileManualLines(current.lines(), stops);
                ExpressFuelCalculationStatus reconciledStatus = manualStatus(reconciledLines, stops);
                if (reconciledStatus != current.status()
                        || !reconciledLines.equals(current.lines())) {
                    Instant reconciledAt = Instant.now();
                    if (!repository.replace(request.requestId(), current.version(), reconciledStatus,
                            request.returnToPickup(), reconciledLines, reconciledAt)) {
                        throw new InvalidDeliveryRequestFilterException(
                                "Le calcul carburant a été modifié entre-temps. Rechargez la page.");
                    }
                    current = new ExpressFuelCalculation(
                            current.requestId(), current.clientDisplayName(), current.pickupLabel(),
                            reconciledStatus, request.returnToPickup(), null,
                            reconciledLines.stream().mapToLong(ExpressFuelLine::finalDistanceMeters)
                                    .reduce(0, Math::addExact),
                            reconciledLines.stream().mapToLong(ExpressFuelLine::suggestedFuelAriary)
                                    .reduce(0, Math::addExact),
                            reconciledLines.stream().mapToLong(ExpressFuelLine::finalFuelAriary)
                                    .reduce(0, Math::addExact),
                            Math.addExact(current.version(), 1), reconciledAt, reconciledLines);
                }
            }

            boolean preserveManual = isManual(current.status())
                    && (!request.requestId().equals(requestId) || expectedVersion == null);
            if (!targetReached || preserveManual) {
                updateStoredEndpoints(request, current, stops, previousEndpoints);
                continue;
            }

            try {
                List<ExpressFuelLine> lines = calculateLines(request, stops, previousEndpoints);
                if (!repository.replace(request.requestId(), current.version(),
                        ExpressFuelCalculationStatus.CALCULATED,
                        request.returnToPickup(), lines, Instant.now())) {
                    throw new InvalidDeliveryRequestFilterException(
                            "Le calcul carburant a été modifié entre-temps. Rechargez la page.");
                }
                updateCalculatedEndpoints(request, lines, previousEndpoints);
            } catch (MissingFuelConfigurationException exception) {
                saveFailure(request, current, ExpressFuelCalculationStatus.CONFIGURATION_MISSING,
                        exception.getMessage());
                updateUnresolvedEndpoints(request, courierIds(stops), previousEndpoints);
            } catch (DependentRouteUnavailableException exception) {
                saveFailure(request, current, ExpressFuelCalculationStatus.ROUTING_FAILED,
                        exception.getMessage());
                updateUnresolvedEndpoints(request, courierIds(stops), previousEndpoints);
            } catch (RuntimeException exception) {
                if (exception instanceof InvalidDeliveryRequestFilterException) throw exception;
                saveFailure(request, current, ExpressFuelCalculationStatus.ROUTING_FAILED,
                        routingFailureMessage(exception));
                updateUnresolvedEndpoints(request, courierIds(stops), previousEndpoints);
            }
        }
        if (!targetReached) return get(requestId);
        return repository.find(requestId).orElseThrow();
    }

    @Transactional
    public ExpressFuelCalculation adjust(UUID requestId, long expectedVersion,
            List<ExpressFuelAdjustment> requestedAdjustments) {
        var request = repository.requestSnapshot(requestId)
                .orElseThrow(() -> new DeliveryRequestNotFoundException(requestId));
        if (repository.isFinanciallyFinalized(requestId)) {
            throw new InvalidDeliveryRequestFilterException(
                    "Le carburant de cette demande est déjà figé dans les rémunérations.");
        }
        if (!repository.isCompletedWithFinalOutcomes(requestId)) {
            throw new InvalidDeliveryRequestFilterException(
                    "Tous les colis doivent avoir un état final avant la saisie du carburant.");
        }
        repository.ensure(requestId, request.returnToPickup());
        ExpressFuelCalculation current = repository.find(requestId).orElseThrow();
        if (current.version() != expectedVersion) {
            throw new InvalidDeliveryRequestFilterException(
                    "Le calcul carburant a été modifié entre-temps. Rechargez la page.");
        }
        List<ExpressFuelAdjustment> adjustments = requestedAdjustments == null
                ? List.of() : List.copyOf(requestedAdjustments);
        if (adjustments.isEmpty()) {
            throw new InvalidDeliveryRequestFilterException("Ajoutez au moins un ajustement carburant.");
        }

        List<DeliveryStop> actualStops = repository.deliveryStops(requestId);
        Map<UUID, List<UUID>> actualPackageIdsByCourier = new LinkedHashMap<>();
        for (DeliveryStop stop : actualStops) {
            if (stop.courierId() != null) {
                actualPackageIdsByCourier.computeIfAbsent(
                        stop.courierId(), ignored -> new ArrayList<>()).add(stop.packageId());
            }
        }
        Map<UUID, ExpressFuelLine> lines = new LinkedHashMap<>();
        current.lines().forEach(line -> lines.put(line.courierId(), line));
        Map<UUID, Boolean> seen = new HashMap<>();
        for (ExpressFuelAdjustment adjustment : adjustments) {
            validateAdjustment(adjustment);
            if (seen.put(adjustment.courierId(), Boolean.TRUE) != null) {
                throw new InvalidDeliveryRequestFilterException(
                        "Un livreur ne peut être ajusté qu'une fois.");
            }
            var courier = courierProfiles.findById(adjustment.courierId());
            ExpressFuelLine previous = lines.get(adjustment.courierId());
            List<UUID> actualPackageIds = actualPackageIdsByCourier.get(adjustment.courierId());
            boolean assignmentChanged = previous != null && actualPackageIds != null
                    && !samePackages(previous.packageIds(), actualPackageIds);
            List<UUID> packageIds = actualPackageIds == null
                    ? previous == null ? List.of() : previous.packageIds()
                    : assignmentChanged || previous == null
                            ? actualPackageIds : previous.packageIds();
            long distance = adjustment.finalDistanceMeters() != null
                    ? adjustment.finalDistanceMeters()
                    : previous == null ? 0 : previous.finalDistanceMeters();
            long suggested = previous == null || assignmentChanged
                    ? 0 : previous.suggestedFuelAriary();
            long finalFuel;
            if (adjustment.finalFuelAmountAriary() != null) {
                finalFuel = adjustment.finalFuelAmountAriary();
            } else {
                if (!courier.fuelReimbursementEnabled()) {
                    finalFuel = 0;
                } else if (courier.fuelEfficiencyKmPerLiter() == null) {
                    throw new InvalidDeliveryRequestFilterException(
                            "Renseignez le rendement carburant du livreur ou saisissez directement le montant.");
                } else {
                    finalFuel = fuelAmount(distance, courier.fuelEfficiencyKmPerLiter(),
                            courier.fuelPricePerLiterAriary());
                }
            }
            lines.put(adjustment.courierId(), new ExpressFuelLine(
                    adjustment.courierId(), courier.courier().name(), courier.courier().contact(),
                    packageIds,
                    previous == null || assignmentChanged ? null : previous.repositionDistanceMeters(),
                    previous == null || assignmentChanged ? null : previous.automaticDistanceMeters(), distance,
                    courier.fuelReimbursementEnabled(), courier.fuelEfficiencyKmPerLiter(),
                    courier.fuelPricePerLiterAriary(), suggested, finalFuel,
                    normalizeReason(adjustment.reason()), true));
        }
        ExpressFuelCalculationStatus status = manualStatus(List.copyOf(lines.values()), actualStops);
        if (!repository.replace(requestId, current.version(), status,
                request.returnToPickup(), List.copyOf(lines.values()), Instant.now())) {
            throw new InvalidDeliveryRequestFilterException(
                    "Le calcul carburant a été modifié entre-temps. Rechargez la page.");
        }
        return recalculateCascade(requestId, null);
    }

    private List<ExpressFuelLine> calculateLines(ExpressFuelRepository.RequestSnapshot request,
            List<DeliveryStop> stops, Map<UUID, PreviousEndpoint> previousEndpoints) {
        Map<UUID, List<DeliveryStop>> byCourier = new LinkedHashMap<>();
        for (DeliveryStop stop : stops) {
            if (stop.courierId() == null) {
                throw new IllegalStateException("Un colis livré n'a pas de livreur réel.");
            }
            byCourier.computeIfAbsent(stop.courierId(), ignored -> new ArrayList<>()).add(stop);
        }

        List<ExpressFuelLine> lines = new ArrayList<>();
        for (Map.Entry<UUID, List<DeliveryStop>> entry : byCourier.entrySet()) {
            var courier = courierProfiles.findById(entry.getKey());
            if (courier.fuelReimbursementEnabled() && courier.fuelEfficiencyKmPerLiter() == null) {
                throw new MissingFuelConfigurationException(
                        "Renseignez la consommation du livreur " + courier.courier().name() + ".");
            }
            List<RoutingPoint> points = new ArrayList<>();
            points.add(new RoutingPoint("pickup", request.pickupLatitude(), request.pickupLongitude()));
            entry.getValue().forEach(stop -> points.add(new RoutingPoint(
                    "package-" + stop.packageId(), stop.latitude(), stop.longitude())));
            var matrix = routingService.matrix(points);
            var optimized = routeOptimizer.optimize(matrix.distancesMeters(), request.returnToPickup());
            if (optimized.destinationIndexes().size() != entry.getValue().size()) {
                throw new IllegalStateException("Tous les points de livraison n'ont pas pu être visités.");
            }
            List<UUID> orderedPackages = optimized.destinationIndexes().stream()
                    .map(index -> entry.getValue().get(index).packageId()).toList();
            Long repositionDistance = repositionDistance(
                    previousEndpoints.get(entry.getKey()), request, entry.getKey());
            long totalDistance = Math.addExact(
                    repositionDistance == null ? 0 : repositionDistance,
                    optimized.distanceMeters());
            long amount = courier.fuelReimbursementEnabled()
                    ? fuelAmount(totalDistance, courier.fuelEfficiencyKmPerLiter(),
                            courier.fuelPricePerLiterAriary())
                    : 0;
            lines.add(new ExpressFuelLine(
                    courier.courier().id(), courier.courier().name(), courier.courier().contact(),
                    orderedPackages, repositionDistance, totalDistance, totalDistance,
                    courier.fuelReimbursementEnabled(), courier.fuelEfficiencyKmPerLiter(),
                    courier.fuelPricePerLiterAriary(), amount, amount, null, false));
        }
        return List.copyOf(lines);
    }

    private Long repositionDistance(PreviousEndpoint previous,
            ExpressFuelRepository.RequestSnapshot request, UUID courierId) {
        if (previous == null) return null;
        if (previous.location() == null) {
            throw new DependentRouteUnavailableException(
                    "Le point final de la demande express précédente du livreur " + courierId
                            + " n'est pas encore connu. Recalculez d'abord cette demande.");
        }
        if (sameLocation(previous.location().latitude(), previous.location().longitude(),
                request.pickupLatitude(), request.pickupLongitude())) return 0L;
        var route = routingService.route(List.of(
                new RoutingPoint("previous-end", previous.location().latitude(), previous.location().longitude()),
                new RoutingPoint("next-pickup", request.pickupLatitude(), request.pickupLongitude())));
        return Math.max(0, Math.round(route.distanceMeters()));
    }

    private void updateStoredEndpoints(ExpressFuelRepository.RequestSnapshot request,
            ExpressFuelCalculation calculation, List<DeliveryStop> stops,
            Map<UUID, PreviousEndpoint> endpoints) {
        if (calculation.status() == ExpressFuelCalculationStatus.CALCULATED
                || isManual(calculation.status())) {
            updateCalculatedEndpoints(request, calculation.lines(), endpoints);
            for (UUID courierId : courierIds(stops)) {
                if (calculation.lines().stream().noneMatch(line -> line.courierId().equals(courierId))) {
                    endpoints.put(courierId, endpointForUnresolved(request));
                }
            }
        } else {
            updateUnresolvedEndpoints(request, courierIds(stops), endpoints);
        }
    }

    private void updateCalculatedEndpoints(ExpressFuelRepository.RequestSnapshot request,
            List<ExpressFuelLine> lines, Map<UUID, PreviousEndpoint> endpoints) {
        for (ExpressFuelLine line : lines) {
            if (request.returnToPickup()) {
                endpoints.put(line.courierId(), new PreviousEndpoint(
                        new ExpressFuelRepository.LocationSnapshot(
                                request.pickupLatitude(), request.pickupLongitude())));
            } else if (!line.packageIds().isEmpty()
                    && (!line.manual() || line.automaticDistanceMeters() != null)) {
                var location = repository.packageLocation(line.packageIds().getLast()).orElse(null);
                endpoints.put(line.courierId(), new PreviousEndpoint(location));
            } else {
                endpoints.put(line.courierId(), new PreviousEndpoint(null));
            }
        }
    }

    private void updateUnresolvedEndpoints(ExpressFuelRepository.RequestSnapshot request,
            List<UUID> courierIds, Map<UUID, PreviousEndpoint> endpoints) {
        PreviousEndpoint endpoint = endpointForUnresolved(request);
        courierIds.forEach(courierId -> endpoints.put(courierId, endpoint));
    }

    private void updatePendingEndpoints(
            List<UUID> courierIds, Map<UUID, PreviousEndpoint> endpoints) {
        courierIds.forEach(courierId -> endpoints.put(courierId, new PreviousEndpoint(null)));
    }

    private PreviousEndpoint endpointForUnresolved(ExpressFuelRepository.RequestSnapshot request) {
        return request.returnToPickup()
                ? new PreviousEndpoint(new ExpressFuelRepository.LocationSnapshot(
                        request.pickupLatitude(), request.pickupLongitude()))
                : new PreviousEndpoint(null);
    }

    private List<UUID> courierIds(List<DeliveryStop> stops) {
        return stops.stream().map(DeliveryStop::courierId).filter(java.util.Objects::nonNull)
                .distinct().toList();
    }

    private ExpressFuelCalculationStatus manualStatus(
            List<ExpressFuelLine> lines, List<DeliveryStop> stops) {
        Map<UUID, List<UUID>> expectedPackages = packageIdsByCourier(stops);
        Map<UUID, ExpressFuelLine> byCourier = new HashMap<>();
        lines.forEach(line -> byCourier.put(line.courierId(), line));
        boolean complete = expectedPackages.entrySet().stream().allMatch(entry -> {
            ExpressFuelLine line = byCourier.get(entry.getKey());
            return line != null && samePackages(line.packageIds(), entry.getValue());
        });
        return complete
                ? ExpressFuelCalculationStatus.MANUAL
                : ExpressFuelCalculationStatus.MANUAL_INCOMPLETE;
    }

    private List<ExpressFuelLine> reconcileManualLines(
            List<ExpressFuelLine> lines, List<DeliveryStop> stops) {
        Map<UUID, List<UUID>> expectedPackages = packageIdsByCourier(stops);
        List<ExpressFuelLine> reconciled = new ArrayList<>();
        for (ExpressFuelLine line : lines) {
            List<UUID> expected = expectedPackages.getOrDefault(line.courierId(), List.of());
            if (samePackages(line.packageIds(), expected)) {
                reconciled.add(line);
                continue;
            }
            // An untouched automatic line no longer represents the actual assignment.
            // A genuinely manual line remains as a possible extra reimbursement, but
            // loses every package that now belongs to another courier and all stale
            // automatic references. The missing/new assignment must then be approved
            // explicitly before the calculation becomes complete again.
            if (!line.manual()) continue;
            List<UUID> retainedPackages = line.packageIds().stream()
                    .filter(expected::contains)
                    .toList();
            reconciled.add(new ExpressFuelLine(
                    line.courierId(), line.courierName(), line.courierContact(),
                    retainedPackages, null, null, line.finalDistanceMeters(),
                    line.fuelReimbursementEnabled(), line.fuelEfficiencyKmPerLiter(),
                    line.fuelPricePerLiterAriary(), 0, line.finalFuelAriary(),
                    line.overrideReason(), true));
        }
        return List.copyOf(reconciled);
    }

    private Map<UUID, List<UUID>> packageIdsByCourier(List<DeliveryStop> stops) {
        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        for (DeliveryStop stop : stops) {
            if (stop.courierId() != null) {
                result.computeIfAbsent(
                        stop.courierId(), ignored -> new ArrayList<>()).add(stop.packageId());
            }
        }
        return result;
    }

    private boolean samePackages(List<UUID> first, List<UUID> second) {
        return first.size() == second.size()
                && first.containsAll(second)
                && second.containsAll(first);
    }

    private boolean sameLocation(double firstLatitude, double firstLongitude,
            double secondLatitude, double secondLongitude) {
        return Math.abs(firstLatitude - secondLatitude) < 0.0000001
                && Math.abs(firstLongitude - secondLongitude) < 0.0000001;
    }

    private boolean isManual(ExpressFuelCalculationStatus status) {
        return status == ExpressFuelCalculationStatus.MANUAL
                || status == ExpressFuelCalculationStatus.MANUAL_INCOMPLETE;
    }

    private void saveFailure(ExpressFuelRepository.RequestSnapshot request,
            ExpressFuelCalculation current, ExpressFuelCalculationStatus status, String message) {
        if (!repository.markFailure(request.requestId(), current.version(), request.returnToPickup(),
                status, truncate(message), Instant.now())) {
            throw new InvalidDeliveryRequestFilterException(
                    "Le calcul carburant a été modifié entre-temps. Rechargez la page.");
        }
    }

    private long fuelAmount(long distanceMeters, BigDecimal efficiencyKmPerLiter, long pricePerLiter) {
        return BigDecimal.valueOf(distanceMeters)
                .divide(BigDecimal.valueOf(1000), 12, RoundingMode.HALF_UP)
                .divide(efficiencyKmPerLiter, 12, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(pricePerLiter))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private void validateAdjustment(ExpressFuelAdjustment adjustment) {
        if (adjustment == null || adjustment.courierId() == null
                || (adjustment.finalDistanceMeters() == null
                    && adjustment.finalFuelAmountAriary() == null)
                || (adjustment.finalDistanceMeters() != null && adjustment.finalDistanceMeters() < 0)
                || (adjustment.finalFuelAmountAriary() != null && adjustment.finalFuelAmountAriary() < 0)) {
            throw new InvalidDeliveryRequestFilterException("Un ajustement carburant est invalide.");
        }
        normalizeReason(adjustment.reason());
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidDeliveryRequestFilterException(
                    "Expliquez toute saisie ou modification manuelle du carburant.");
        }
        String normalized = reason.trim();
        if (normalized.length() > 1000) {
            throw new InvalidDeliveryRequestFilterException(
                    "La raison du calcul manuel ne peut pas dépasser 1000 caractères.");
        }
        return normalized;
    }

    private String routingFailureMessage(RuntimeException exception) {
        String detail = exception.getMessage();
        return detail == null || detail.isBlank()
                ? "Le service de calcul routier est momentanément indisponible."
                : "Le trajet routier n'a pas pu être calculé : " + detail;
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) return "Le calcul carburant a échoué.";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private ExpressFuelCalculation waiting(ExpressFuelRepository.RequestSnapshot request) {
        return new ExpressFuelCalculation(
                request.requestId(), request.clientDisplayName(), request.pickupLabel(),
                ExpressFuelCalculationStatus.WAITING_FOR_OUTCOMES, request.returnToPickup(),
                null, 0, 0, 0, 0, null, List.of());
    }

    private static final class MissingFuelConfigurationException extends RuntimeException {
        MissingFuelConfigurationException(String message) {
            super(message);
        }
    }

    private static final class DependentRouteUnavailableException extends RuntimeException {
        DependentRouteUnavailableException(String message) {
            super(message);
        }
    }

    private record PreviousEndpoint(ExpressFuelRepository.LocationSnapshot location) {}
}
