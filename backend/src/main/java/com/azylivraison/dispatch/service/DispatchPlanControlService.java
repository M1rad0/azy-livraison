package com.azylivraison.dispatch.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.azylivraison.dispatch.domain.CreateDispatchCommand;
import com.azylivraison.dispatch.domain.DispatchCreationResult;
import com.azylivraison.dispatch.domain.DispatchPackageOutcome;
import com.azylivraison.dispatch.domain.DispatchPlanCourierSnapshot;
import com.azylivraison.dispatch.domain.DispatchPlanDetails;
import com.azylivraison.dispatch.domain.DispatchPlanRecord;
import com.azylivraison.dispatch.domain.DispatchStatus;
import com.azylivraison.dispatch.domain.DispatchStop;
import com.azylivraison.dispatch.domain.ManualDispatchAssignment;
import com.azylivraison.dispatch.repository.DispatchPlanRepository;
import com.azylivraison.dispatch.repository.DispatchPlanRepository.DispatchDayControlState;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OperationalPolicySnapshot;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.routing.domain.RoutingLeg;
import com.azylivraison.routing.domain.RoutingPoint;
import com.azylivraison.routing.service.RoutingRouteResult;
import com.azylivraison.routing.service.RoutingService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

@Service
public class DispatchPlanControlService {

    private final DispatchPlanRepository repository;
    private final CreateDispatchService createService;
    private final RoutingService routingService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public DispatchPlanControlService(
            DispatchPlanRepository repository,
            CreateDispatchService createService,
            RoutingService routingService,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.createService = createService;
        this.routingService = routingService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public DispatchPlanDetails cancel(
            UUID planId,
            long expectedVersion,
            String reason,
            UUID actorAccountId) {
        String normalizedReason = requireReason(reason);
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            cancelInternal(planId, expectedVersion, normalizedReason, actorAccountId);
            return createService.get(planId);
        }));
    }

    public DispatchCreationResult regenerate(
            UUID planId,
            long expectedVersion,
            String reason,
            List<UUID> courierIds,
            Location hubOverride,
            boolean includeNewPending,
            String idempotencyKey,
            UUID actorAccountId) {
        String normalizedReason = requireReason(reason);
        validateCourierIds(courierIds);
        DispatchPlanRecord current = repository.findById(planId).orElseThrow(DispatchNotFoundException::new);
        List<UUID> cohort = repository.findPlanPackageIds(planId);
        if (cohort.isEmpty()) {
            throw new DispatchConflictException("Le plan ne contient aucun colis à régénérer.");
        }

        if (current.status() == DispatchStatus.SUPERSEDED) {
            DispatchPlanRecord replay = repository
                    .findByActorAndIdempotencyKey(actorAccountId, idempotencyKey)
                    .orElseThrow(() -> new DispatchConflictException(
                            "Ce plan a déjà été remplacé. Utilisez la clé de la régénération existante."));
            if (!planId.equals(replay.supersedesPlanId())) {
                throw new DispatchConflictException(
                        "Cette clé de sécurité appartient à une autre régénération.");
            }
        } else if (current.status() == DispatchStatus.READY
                || current.status() == DispatchStatus.READY_WITH_WARNINGS) {
            cohort = Objects.requireNonNull(transactionTemplate.execute(status ->
                    cancelInternal(planId, expectedVersion, normalizedReason, actorAccountId)));
        } else if (current.status() == DispatchStatus.CANCELLED) {
            if (current.version() != expectedVersion) {
                throw new DispatchConflictException("Le plan a été modifié entre-temps. Rechargez-le.");
            }
        } else if (current.status() != DispatchStatus.SUPERSEDED) {
            throw new DispatchConflictException("Ce plan ne peut pas être régénéré.");
        }

        CreateDispatchCommand command = new CreateDispatchCommand(
                current.serviceDate(),
                courierIds,
                hubOverride,
                idempotencyKey,
                actorAccountId,
                includeNewPending ? null : cohort);
        DispatchCreationResult generated = createService.create(command);
        DispatchPlanRecord generatedPlan = generated.details().plan();
        if (current.status() == DispatchStatus.SUPERSEDED) {
            if (!planId.equals(generatedPlan.supersedesPlanId())) {
                throw new DispatchConflictException(
                        "Cette régénération a déjà été finalisée avec une autre clé de sécurité.");
            }
            return DispatchCreationResult.replayed(createService.get(generatedPlan.id()));
        }

        transactionTemplate.executeWithoutResult(status -> repository.linkReplacement(
                planId,
                generatedPlan.id(),
                actorAccountId,
                normalizedReason,
                Instant.now()));
        DispatchPlanDetails refreshed = createService.get(generatedPlan.id());
        return generated.replayed()
                ? DispatchCreationResult.replayed(refreshed)
                : DispatchCreationResult.created(refreshed);
    }

    public DispatchPlanDetails updateManualLayout(
            UUID planId,
            long expectedVersion,
            String reason,
            List<ManualDispatchAssignment> assignments,
            UUID actorAccountId) {
        String normalizedReason = requireReason(reason);
        ManualClaim claim = Objects.requireNonNull(transactionTemplate.execute(status ->
                claimManualLayout(planId, expectedVersion, assignments)));
        ManualProjection projection;
        try {
            projection = projectManualLayout(claim);
        } catch (DispatchInputException | DispatchConflictException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DispatchExecutionException(
                    planId,
                    "Les trajets de la répartition manuelle n'ont pas pu être calculés.",
                    exception);
        }
        transactionTemplate.executeWithoutResult(status -> applyManualLayout(
                claim, projection, normalizedReason, actorAccountId));
        return createService.get(planId);
    }

    private List<UUID> cancelInternal(
            UUID planId,
            long expectedVersion,
            String reason,
            UUID actorAccountId) {
        DispatchPlanRecord plan = repository.findByIdForUpdate(planId)
                .orElseThrow(DispatchNotFoundException::new);
        if (plan.version() != expectedVersion) {
            throw new DispatchConflictException("Le plan a été modifié entre-temps. Rechargez-le.");
        }
        if (plan.status() != DispatchStatus.READY && plan.status() != DispatchStatus.READY_WITH_WARNINGS) {
            throw new DispatchConflictException("Seul le plan actif et prêt peut être annulé ou régénéré.");
        }
        DispatchDayControlState day = repository.lockDayForControl(plan.serviceDate());
        if (day == null || !"DISPATCHED".equals(day.status()) || !plan.id().equals(day.dispatchPlanId())) {
            throw new DispatchConflictException("Ce plan n'est plus le plan actif de la journée.");
        }
        int finalOutcomes = repository.countFinalOutcomes(planId);
        if (finalOutcomes > 0) {
            throw new DispatchConflictException(
                    "Le plan ne peut plus être modifié : " + finalOutcomes
                            + " colis ont déjà un résultat renseigné.");
        }
        List<UUID> cohort = repository.findPlanPackageIds(planId);
        if (cohort.size() != plan.selectedPackageCount()) {
            throw new IllegalStateException("La cohorte enregistrée du plan est incomplète.");
        }
        repository.cancelActivePlan(plan, actorAccountId, reason, Instant.now());
        return cohort;
    }

    private ManualClaim claimManualLayout(
            UUID planId,
            long expectedVersion,
            List<ManualDispatchAssignment> assignments) {
        DispatchPlanRecord plan = repository.findByIdForUpdate(planId)
                .orElseThrow(DispatchNotFoundException::new);
        if (plan.version() != expectedVersion) {
            throw new DispatchConflictException("Le plan a été modifié entre-temps. Rechargez-le.");
        }
        if (plan.status() != DispatchStatus.READY && plan.status() != DispatchStatus.READY_WITH_WARNINGS) {
            throw new DispatchConflictException("Seul le plan actif et prêt peut être modifié.");
        }
        DispatchDayControlState day = repository.lockDayForControl(plan.serviceDate());
        if (day == null || !"DISPATCHED".equals(day.status()) || !plan.id().equals(day.dispatchPlanId())) {
            throw new DispatchConflictException("Ce plan n'est plus le plan actif de la journée.");
        }
        if (repository.countFinalOutcomes(planId) > 0) {
            throw new DispatchConflictException(
                    "La répartition ne peut plus être modifiée après la saisie d'un résultat de livraison.");
        }

        List<DispatchPackageOutcome> outcomes = repository.findPackageOutcomes(planId);
        List<DispatchPackageOutcome> assignedOutcomes = outcomes.stream()
                .filter(DispatchPackageOutcome::assigned)
                .toList();
        validateAssignments(assignments, assignedOutcomes);
        List<DispatchPlanCourierSnapshot> couriers = repository.findCouriers(planId);
        Map<UUID, DispatchPlanCourierSnapshot> courierById = couriers.stream()
                .collect(Collectors.toMap(DispatchPlanCourierSnapshot::courierId, Function.identity()));
        Map<UUID, BigDecimal> loadByCourier = new HashMap<>();
        Map<UUID, DispatchPackageOutcome> outcomeById = assignedOutcomes.stream()
                .collect(Collectors.toMap(DispatchPackageOutcome::packageId, Function.identity()));
        for (ManualDispatchAssignment assignment : assignments) {
            DispatchPlanCourierSnapshot courier = courierById.get(assignment.distributorCourierId());
            if (courier == null) {
                throw new DispatchInputException("Un livreur choisi ne fait pas partie du plan.");
            }
            if (courier.hubDepartureAt() == null) {
                throw new DispatchInputException(
                        "Le livreur " + courier.name()
                                + " n'a pas de passage calculé au point de ralliement. Régénérez le plan pour l'ajouter.");
            }
            loadByCourier.merge(
                    courier.courierId(),
                    outcomeById.get(assignment.packageId()).effectiveWeightKg(),
                    BigDecimal::add);
        }
        for (Map.Entry<UUID, BigDecimal> load : loadByCourier.entrySet()) {
            DispatchPlanCourierSnapshot courier = courierById.get(load.getKey());
            if (load.getValue().compareTo(courier.maxWeightKg()) > 0) {
                throw new DispatchInputException(
                        "La répartition dépasse la capacité de " + courier.name() + " ("
                                + load.getValue().stripTrailingZeros().toPlainString() + " kg sur "
                                + courier.maxWeightKg().stripTrailingZeros().toPlainString() + " kg).");
            }
        }
        OperationalPolicySnapshot policy = readPolicy(plan.policySnapshotJson());
        return new ManualClaim(
                plan,
                assignments.stream().sorted(Comparator
                        .comparing(ManualDispatchAssignment::distributorCourierId)
                        .thenComparingInt(ManualDispatchAssignment::deliveryOrder)).toList(),
                assignedOutcomes,
                couriers,
                repository.findStops(planId),
                policy.deliveryServiceSeconds());
    }

    private ManualProjection projectManualLayout(ManualClaim claim) {
        Map<UUID, DispatchPackageOutcome> outcomeById = claim.outcomes().stream()
                .collect(Collectors.toMap(DispatchPackageOutcome::packageId, Function.identity()));
        Map<UUID, List<ManualDispatchAssignment>> byCourier = claim.assignments().stream()
                .collect(Collectors.groupingBy(
                        ManualDispatchAssignment::distributorCourierId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<UUID, Long> oldDeliveryDistance = claim.stops().stream()
                .filter(stop -> "DELIVERY".equals(stop.type()))
                .collect(Collectors.groupingBy(
                        DispatchStop::courierId,
                        Collectors.summingLong(DispatchStop::distanceFromPreviousMeters)));
        Map<UUID, Long> oldDeliveryDuration = claim.stops().stream()
                .filter(stop -> "DELIVERY".equals(stop.type()))
                .collect(Collectors.groupingBy(
                        DispatchStop::courierId,
                        Collectors.summingLong(DispatchStop::durationFromPreviousSeconds)));
        Map<UUID, Integer> prefixOrder = claim.stops().stream()
                .filter(stop -> !"DELIVERY".equals(stop.type()))
                .collect(Collectors.toMap(
                        DispatchStop::courierId,
                        DispatchStop::order,
                        Math::max));

        List<DispatchStop> deliveryStops = new ArrayList<>();
        Map<UUID, ManualCourierProjection> courierProjections = new LinkedHashMap<>();
        Map<UUID, Instant> estimatedByPackage = new HashMap<>();
        long totalDistance = 0;
        long totalDuration = 0;
        Instant planCompletedAt = claim.plan().redistributionCompletedAt();

        for (DispatchPlanCourierSnapshot courier : claim.couriers()) {
            List<ManualDispatchAssignment> courierAssignments = byCourier.getOrDefault(courier.courierId(), List.of());
            long baseDistance = Math.max(0, valueOrZero(courier.routeDistanceMeters())
                    - oldDeliveryDistance.getOrDefault(courier.courierId(), 0L));
            long baseDuration = Math.max(0, valueOrZero(courier.routeDurationSeconds())
                    - oldDeliveryDuration.getOrDefault(courier.courierId(), 0L)
                    - Math.multiplyExact(
                            claim.deliveryServiceSeconds(),
                            (long) claim.stops().stream()
                                    .filter(stop -> "DELIVERY".equals(stop.type())
                                            && courier.courierId().equals(stop.courierId()))
                                    .count()));
            long deliveryDistance = 0;
            long deliveryDuration = 0;
            Instant completedAt = courier.hubDepartureAt();
            BigDecimal load = BigDecimal.ZERO;

            if (!courierAssignments.isEmpty()) {
                List<RoutingPoint> points = new ArrayList<>();
                points.add(new RoutingPoint(
                        "hub-" + courier.courierId(),
                        claim.plan().hub().latitude(),
                        claim.plan().hub().longitude()));
                for (ManualDispatchAssignment assignment : courierAssignments) {
                    DispatchPackageOutcome outcome = outcomeById.get(assignment.packageId());
                    points.add(new RoutingPoint(
                            outcome.packageId().toString(),
                            outcome.destination().latitude(),
                            outcome.destination().longitude()));
                    load = load.add(outcome.effectiveWeightKg());
                }
                RoutingRouteResult route = routingService.route(points);
                if (route.legs().size() != courierAssignments.size()) {
                    throw new DispatchExecutionException(
                            claim.plan().id(),
                            "Le service de routage n'a pas retourné tous les segments du trajet manuel.",
                            null);
                }
                Instant cursor = claim.plan().redistributionCompletedAt();
                long cumulativeDistance = 0;
                long cumulativeDuration = 0;
                int stopOrder = prefixOrder.getOrDefault(courier.courierId(), 0) + 1;
                for (int index = 0; index < courierAssignments.size(); index++) {
                    ManualDispatchAssignment assignment = courierAssignments.get(index);
                    DispatchPackageOutcome outcome = outcomeById.get(assignment.packageId());
                    RoutingLeg leg = route.legs().get(index);
                    long distance = nonNegativeRounded(leg.distanceMeters());
                    long duration = nonNegativeRounded(leg.durationSeconds());
                    cursor = cursor.plusSeconds(duration);
                    Instant arrival = cursor;
                    estimatedByPackage.put(outcome.packageId(), arrival);
                    cumulativeDistance = Math.addExact(cumulativeDistance, distance);
                    cumulativeDuration = Math.addExact(cumulativeDuration, duration);
                    deliveryStops.add(new DispatchStop(
                            UUID.randomUUID(),
                            courier.courierId(),
                            stopOrder++,
                            "DELIVERY",
                            outcome.deliveryRequestId(),
                            outcome.packageId(),
                            outcome.recipientName(),
                            outcome.destination(),
                            arrival,
                            distance,
                            duration,
                            cumulativeDistance,
                            cumulativeDuration));
                    cursor = cursor.plusSeconds(claim.deliveryServiceSeconds());
                    cumulativeDuration = Math.addExact(cumulativeDuration, claim.deliveryServiceSeconds());
                }
                deliveryDistance = nonNegativeRounded(route.distanceMeters());
                deliveryDuration = Math.addExact(
                        nonNegativeRounded(route.durationSeconds()),
                        Math.multiplyExact(claim.deliveryServiceSeconds(), courierAssignments.size()));
                completedAt = cursor;
            }
            long routeDistance = Math.addExact(baseDistance, deliveryDistance);
            long routeDuration = Math.addExact(baseDuration, deliveryDuration);
            totalDistance = Math.addExact(totalDistance, routeDistance);
            totalDuration = Math.addExact(totalDuration, routeDuration);
            if (completedAt != null && (planCompletedAt == null || completedAt.isAfter(planCompletedAt))) {
                planCompletedAt = completedAt;
            }
            courierProjections.put(courier.courierId(), new ManualCourierProjection(
                    !courierAssignments.isEmpty(), load, completedAt, routeDistance, routeDuration));
        }
        return new ManualProjection(
                deliveryStops,
                courierProjections,
                estimatedByPackage,
                totalDistance,
                totalDuration,
                planCompletedAt);
    }

    private void applyManualLayout(
            ManualClaim claim,
            ManualProjection projection,
            String reason,
            UUID actorAccountId) {
        DispatchPlanRecord locked = repository.findByIdForUpdate(claim.plan().id())
                .orElseThrow(DispatchNotFoundException::new);
        if (locked.version() != claim.plan().version()) {
            throw new DispatchConflictException("Le plan a été modifié pendant le calcul. Rechargez-le.");
        }
        DispatchDayControlState day = repository.lockDayForControl(locked.serviceDate());
        if (day == null || !"DISPATCHED".equals(day.status()) || !locked.id().equals(day.dispatchPlanId())
                || repository.countFinalOutcomes(locked.id()) > 0) {
            throw new DispatchConflictException("Le plan n'est plus modifiable.");
        }

        for (ManualDispatchAssignment assignment : claim.assignments()) {
            repository.updateManualPackageOutcome(
                    locked.id(),
                    assignment.packageId(),
                    assignment.distributorCourierId(),
                    assignment.deliveryOrder(),
                    projection.estimatedByPackage().get(assignment.packageId()));
        }
        repository.replaceDeliveryStops(locked.id(), projection.deliveryStops());
        for (DispatchPlanCourierSnapshot courier : claim.couriers()) {
            ManualCourierProjection result = projection.couriers().get(courier.courierId());
            repository.updateManualCourierResult(
                    locked.id(),
                    courier.courierId(),
                    result.distributionRole(),
                    result.loadKg(),
                    result.completedAt(),
                    result.routeDistanceMeters(),
                    result.routeDurationSeconds());
        }
        repository.markManual(
                locked,
                actorAccountId,
                reason,
                writeJson(claim.assignments()),
                (int) claim.couriers().stream()
                        .filter(courier -> courier.collectionRole()
                                || projection.couriers().get(courier.courierId()).distributionRole())
                        .count(),
                projection.totalDistanceMeters(),
                projection.totalDurationSeconds(),
                projection.completedAt(),
                Instant.now());
    }

    private void validateAssignments(
            List<ManualDispatchAssignment> assignments,
            List<DispatchPackageOutcome> assignedOutcomes) {
        if (assignments == null || assignments.isEmpty()) {
            throw new DispatchInputException("La répartition manuelle est obligatoire.");
        }
        Set<UUID> expected = assignedOutcomes.stream()
                .map(DispatchPackageOutcome::packageId)
                .collect(Collectors.toSet());
        Set<UUID> supplied = assignments.stream()
                .map(ManualDispatchAssignment::packageId)
                .collect(Collectors.toSet());
        if (supplied.size() != assignments.size()) {
            throw new DispatchInputException("Un colis apparaît plusieurs fois dans la répartition manuelle.");
        }
        if (!supplied.equals(expected)) {
            throw new DispatchInputException(
                    "La répartition doit contenir exactement les " + expected.size()
                            + " colis actuellement affectés.");
        }
        if (assignments.stream().anyMatch(assignment -> assignment.distributorCourierId() == null
                || assignment.deliveryOrder() <= 0)) {
            throw new DispatchInputException("Un livreur ou un ordre de livraison est invalide.");
        }
        Map<UUID, List<Integer>> orders = assignments.stream().collect(Collectors.groupingBy(
                ManualDispatchAssignment::distributorCourierId,
                Collectors.mapping(ManualDispatchAssignment::deliveryOrder, Collectors.toList())));
        for (List<Integer> courierOrders : orders.values()) {
            List<Integer> sorted = courierOrders.stream().sorted().toList();
            List<Integer> expectedOrders = IntStream.rangeClosed(1, sorted.size()).boxed().toList();
            if (!sorted.equals(expectedOrders)) {
                throw new DispatchInputException(
                        "L'ordre d'un livreur doit être continu et commencer à 1.");
            }
        }
    }

    private void validateCourierIds(List<UUID> courierIds) {
        if (courierIds == null || courierIds.isEmpty()) {
            throw new DispatchInputException("Sélectionnez au moins un livreur.");
        }
        if (courierIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(courierIds).size() != courierIds.size()) {
            throw new DispatchInputException("La sélection de livreurs contient un doublon ou une valeur invalide.");
        }
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DispatchInputException("Indiquez la raison de cette modification.");
        }
        String normalized = reason.trim();
        if (normalized.length() > 1000) {
            throw new DispatchInputException("La raison ne peut pas dépasser 1000 caractères.");
        }
        return normalized;
    }

    private OperationalPolicySnapshot readPolicy(String json) {
        try {
            return objectMapper.readValue(json, OperationalPolicySnapshot.class);
        } catch (Exception exception) {
            throw new IllegalStateException("La politique enregistrée du plan est illisible.", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("La modification du plan ne peut pas être auditée.", exception);
        }
    }

    private long nonNegativeRounded(double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new DispatchInputException("Le service de routage a retourné une distance ou durée invalide.");
        }
        return Math.round(value);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0 : value;
    }

    private record ManualClaim(
            DispatchPlanRecord plan,
            List<ManualDispatchAssignment> assignments,
            List<DispatchPackageOutcome> outcomes,
            List<DispatchPlanCourierSnapshot> couriers,
            List<DispatchStop> stops,
            long deliveryServiceSeconds) {
    }

    private record ManualCourierProjection(
            boolean distributionRole,
            BigDecimal loadKg,
            Instant completedAt,
            long routeDistanceMeters,
            long routeDurationSeconds) {
    }

    private record ManualProjection(
            List<DispatchStop> deliveryStops,
            Map<UUID, ManualCourierProjection> couriers,
            Map<UUID, Instant> estimatedByPackage,
            long totalDistanceMeters,
            long totalDurationSeconds,
            Instant completedAt) {
    }
}
