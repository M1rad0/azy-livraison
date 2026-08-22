package com.azylivraison.optimization.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.azylivraison.optimization.config.OptimizationProperties;
import com.azylivraison.optimization.domain.OperationalPolicy;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.CourierHubManifest;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.HubExecution;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.HubRole;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.PackageReference;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.PackageTransfer;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.StrategyMetrics;
import com.azylivraison.optimization.domain.OptimizationResult;
import com.azylivraison.optimization.domain.OptimizationResult.CourierRoute;
import com.azylivraison.optimization.domain.OptimizationResult.DataContext;
import com.azylivraison.optimization.domain.OptimizationResult.OptimizationStatus;
import com.azylivraison.optimization.domain.OptimizationResult.OptimizationSummary;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStop;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStopType;
import com.azylivraison.optimization.domain.OptimizationResult.UnassignedPackage;
import com.azylivraison.optimization.domain.OptimizationResult.UnassignedReason;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryClient;
import com.azylivraison.planning.domain.DeliveryGroup;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.DeliveryPlan;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.routing.domain.RoutingPoint;
import com.azylivraison.routing.service.RoutingMatrixResult;
import com.azylivraison.routing.service.RoutingService;

import org.springframework.stereotype.Service;

/** Builds the physical two-phase scenario: collect, meet, redistribute, deliver. */
@Service
class HubOptimizationService {

    private static final String RESULT_SCHEMA_VERSION = "2.1";
    private static final String OPTIMIZER_NAME = "OR_TOOLS_HUB";
    private static final String OPTIMIZER_VERSION = "9.15.6755";
    private static final int MAX_MEETING_STABILIZATION_ITERATIONS = 10;
    private static final int MAX_COLLECTION_FEEDBACK_ATTEMPTS = 2;
    private static final ZoneId ANTANANARIVO = ZoneId.of("Indian/Antananarivo");

    private final RoutingService routingService;
    private final HubCollectionTaskFactory taskFactory;
    private final HubCollectionOrToolsRoutingOptimizer collectionOptimizer;
    private final HubDistributionOrToolsRoutingOptimizer distributionOptimizer;
    private final OperationalPolicy operationalPolicy;
    private final OptimizationScenarioEvaluator scenarioEvaluator = new OptimizationScenarioEvaluator();

    HubOptimizationService(
            RoutingService routingService,
            HubCollectionTaskFactory taskFactory,
            HubCollectionOrToolsRoutingOptimizer collectionOptimizer,
            HubDistributionOrToolsRoutingOptimizer distributionOptimizer,
            OptimizationProperties properties) {
        this.routingService = routingService;
        this.taskFactory = taskFactory;
        this.collectionOptimizer = collectionOptimizer;
        this.distributionOptimizer = distributionOptimizer;
        this.operationalPolicy = properties.operationalPolicy();
    }

    HubOptimizationOutcome optimize(DeliveryPlan plan) {
        requireHubInputs(plan);
        List<MultiClientOptimizationJob> allJobs = jobs(plan);
        List<HubCollectionTask> tasks = taskFactory.create(plan.clients(), plan.couriers());
        if (tasks.isEmpty()) {
            return emptyOutcome(plan, allJobs);
        }

        CollectionSetup collectionSetup = collectionSetup(plan, tasks);
        List<PhaseSolution> candidates = new ArrayList<>();
        PhaseSolution current = solvePhases(plan, allJobs, collectionSetup);
        candidates.add(current);
        Set<java.time.Instant> attemptedDeadlines = new HashSet<>();
        for (int attempt = 0; attempt < MAX_COLLECTION_FEEDBACK_ATTEMPTS; attempt++) {
            OffsetDateTime deadline = feedbackDeadline(plan, current);
            if (deadline == null || !attemptedDeadlines.add(deadline.toInstant())) break;
            CollectionSetup constrainedSetup = withCollectionDeadline(collectionSetup, deadline);
            current = solvePhases(plan, allJobs, constrainedSetup);
            candidates.add(current);
        }

        long totalComputationMillis = candidates.stream()
                .mapToLong(candidate -> Math.addExact(
                        candidate.collection().computationTimeMillis(),
                        candidate.distribution().computationTimeMillis()))
                .reduce(0L, Math::addExact);
        HubOptimizationOutcome best = null;
        for (PhaseSolution candidate : candidates) {
            HubOptimizationOutcome outcome = buildOutcome(plan, allJobs, candidate, totalComputationMillis);
            if (best == null || isBetter(outcome.metrics(), best.metrics())) {
                best = outcome;
            }
        }
        return Objects.requireNonNull(best);
    }

    private PhaseSolution solvePhases(
            DeliveryPlan plan,
            List<MultiClientOptimizationJob> allJobs,
            CollectionSetup collectionSetup) {
        HubCollectionSolvedOptimization collection = collectionOptimizer.solve(collectionSetup.problem());
        Map<String, HubCollectionSolvedOptimization.SolvedRoute> collectionRouteByCourier =
                collectionRoutesByCourier(collectionSetup.problem(), collection);
        Map<String, String> collectorByPackage = collectorsByPackage(collectionSetup.problem(), collection);
        Set<String> collectedPackageIds = Set.copyOf(collectorByPackage.keySet());
        List<MultiClientOptimizationJob> collectedJobs = allJobs.stream()
                .filter(job -> collectedPackageIds.contains(job.deliveryPackage().id()))
                .toList();

        Map<String, CourierApproach> approaches = approachesToHub(plan, collectionSetup);
        StabilizedDistribution distribution = stabilizeDistribution(
                plan,
                collectedJobs,
                collectionSetup,
                collection,
                collectionRouteByCourier,
                approaches);

        return new PhaseSolution(
                collectionSetup,
                collection,
                collectionRouteByCourier,
                collectorByPackage,
                collectedPackageIds,
                approaches,
                distribution);
    }

    private HubOptimizationOutcome buildOutcome(
            DeliveryPlan plan,
            List<MultiClientOptimizationJob> allJobs,
            PhaseSolution phase,
            long totalComputationMillis) {
        CollectionSetup collectionSetup = phase.collectionSetup();
        HubCollectionSolvedOptimization collection = phase.collection();
        Map<String, HubCollectionSolvedOptimization.SolvedRoute> collectionRouteByCourier =
                phase.collectionRoutes();
        Map<String, String> collectorByPackage = phase.collectorByPackage();
        Set<String> collectedPackageIds = phase.collectedPackageIds();
        Map<String, CourierApproach> approaches = phase.approaches();
        StabilizedDistribution distribution = phase.distribution();

        Map<String, HubDistributionSolvedOptimization.SolvedRoute> distributionRouteByCourier =
                distributionRoutesByCourier(distribution);
        Map<String, String> distributorByPackage = distributorsByPackage(distribution);
        Set<String> participantIds = new LinkedHashSet<>(collectionRouteByCourier.keySet());
        participantIds.addAll(distributionRouteByCourier.keySet());

        List<String> warnings = new ArrayList<>();
        if (!distribution.stable()) {
            warnings.add("Le rendez-vous a atteint la limite de 10 recalculs ; l'heure retenue reste réalisable.");
        }
        Map<String, UnassignedPackage> unassignedByPackage = unassignedPackages(
                plan,
                allJobs,
                collectedPackageIds,
                distribution,
                warnings);

        OffsetDateTime redistributionCompletedAt = distribution.meetingAt() == null
                ? null
                : distribution.meetingAt().plus(operationalPolicy.hubTransferDuration());
        List<CourierRoute> routes = buildCombinedRoutes(
                plan,
                collectionSetup.problem(),
                collection,
                collectionRouteByCourier,
                distribution,
                distributionRouteByCourier,
                approaches,
                participantIds,
                redistributionCompletedAt);
        OptimizationResult result = result(
                plan,
                allJobs.size(),
                routes,
                unassignedByPackage.values().stream().toList(),
                warnings,
                collectionSetup.matrix(),
                collection,
                distribution,
                totalComputationMillis);
        HubExecution hubExecution = hubExecution(
                plan,
                allJobs,
                collectionSetup.problem(),
                collection,
                collectionRouteByCourier,
                distribution,
                distributionRouteByCourier,
                approaches,
                collectorByPackage,
                distributorByPackage,
                participantIds,
                redistributionCompletedAt);
        StrategyMetrics metrics = scenarioEvaluator.evaluate(plan, result, operationalPolicy);
        return new HubOptimizationOutcome(result, hubExecution, metrics);
    }

    private CollectionSetup withCollectionDeadline(CollectionSetup setup, OffsetDateTime deadline) {
        HubCollectionProblem source = setup.problem();
        HubCollectionProblem constrained = new HubCollectionProblem(
                source.couriers(),
                source.tasks(),
                source.hubNode(),
                source.courierStartNodes(),
                source.taskNodes(),
                source.matrixPointByNode(),
                source.distancesMeters(),
                deadline);
        return new CollectionSetup(
                constrained,
                setup.matrix(),
                setup.startMatrixPoints(),
                setup.hubMatrixPoint());
    }

    private OffsetDateTime feedbackDeadline(DeliveryPlan plan, PhaseSolution phase) {
        StabilizedDistribution distribution = phase.distribution();
        if (distribution.problem() == null
                || distribution.solved() == null
                || distribution.meetingAt() == null) {
            return null;
        }

        List<OffsetDateTime> deadlines = new ArrayList<>();
        for (Integer droppedIndex : distribution.solved().droppedJobIndexes()) {
            MultiClientOptimizationJob job = distribution.problem().jobs().get(droppedIndex);
            if (job.deliveryPackage().deliveryWindow() == null) continue;
            OffsetDateTime latestServiceStart = job.deliveryPackage().deliveryWindow().endAt()
                    .plus(operationalPolicy.deliveryWindowLateTolerance());
            addFeedbackDeadline(plan, distribution, droppedIndex, latestServiceStart, deadlines);
        }
        for (HubDistributionSolvedOptimization.SolvedRoute route : distribution.solved().routes()) {
            for (HubDistributionSolvedOptimization.SolvedVisit visit : route.visits()) {
                if (visit.latenessSeconds() <= 0) continue;
                MultiClientOptimizationJob job = distribution.problem().jobs().get(visit.jobIndex());
                if (job.deliveryPackage().deliveryWindow() == null) continue;
                addFeedbackDeadline(
                        plan,
                        distribution,
                        visit.jobIndex(),
                        job.deliveryPackage().deliveryWindow().endAt(),
                        deadlines);
            }
        }
        return deadlines.stream()
                .filter(deadline -> deadline.toInstant().isBefore(distribution.meetingAt().toInstant()))
                .max(Comparator.comparing(OffsetDateTime::toInstant))
                .orElse(null);
    }

    private void addFeedbackDeadline(
            DeliveryPlan plan,
            StabilizedDistribution distribution,
            int jobIndex,
            OffsetDateTime latestServiceStart,
            List<OffsetDateTime> deadlines) {
        MultiClientOptimizationJob job = distribution.problem().jobs().get(jobIndex);
        Double distance = distribution.problem().distancesMeters()
                .get(distribution.problem().hubNode())
                .get(distribution.problem().deliveryNodes()[jobIndex]);
        if (distance == null || !Double.isFinite(distance)) return;
        long fastestTravel = plan.couriers().stream()
                .filter(courier -> courier.maxWeightKg().compareTo(job.deliveryPackage().weightKg()) >= 0)
                .mapToLong(courier -> travelSeconds(Math.max(0, distance), courier))
                .min()
                .orElse(Long.MAX_VALUE);
        if (fastestTravel == Long.MAX_VALUE) return;
        deadlines.add(latestServiceStart
                .minus(operationalPolicy.hubTransferDuration())
                .minusSeconds(fastestTravel));
    }

    private boolean isBetter(StrategyMetrics candidate, StrategyMetrics current) {
        if (candidate.assignedPackageCount() != current.assignedPackageCount()) {
            return candidate.assignedPackageCount() > current.assignedPackageCount();
        }
        if (candidate.onTimePackageCount() != current.onTimePackageCount()) {
            return candidate.onTimePackageCount() > current.onTimePackageCount();
        }
        if (candidate.totalLatenessSeconds() != current.totalLatenessSeconds()) {
            return candidate.totalLatenessSeconds() < current.totalLatenessSeconds();
        }
        if (candidate.totalDistanceMeters() != current.totalDistanceMeters()) {
            return candidate.totalDistanceMeters() < current.totalDistanceMeters();
        }
        int completion = compareCompletion(candidate.completedAt(), current.completedAt());
        if (completion != 0) return completion < 0;
        return candidate.usedCourierCount() < current.usedCourierCount();
    }

    private int compareCompletion(OffsetDateTime candidate, OffsetDateTime current) {
        if (candidate == null && current == null) return 0;
        if (candidate == null) return 1;
        if (current == null) return -1;
        return candidate.toInstant().compareTo(current.toInstant());
    }

    private void requireHubInputs(DeliveryPlan plan) {
        if (plan == null) throw new IllegalArgumentException("Le plan est obligatoire.");
        requireCoordinates(plan.redistributionHub(), "Le siège doit être confirmé sur la carte.");
        if (plan.couriers().isEmpty()) {
            throw new OptimizationInputException("Ajouter au moins un livreur.");
        }
        for (Courier courier : plan.couriers()) {
            if (courier.departureAt() == null) {
                throw new OptimizationInputException("L'heure de départ du livreur " + courier.name() + " est obligatoire.");
            }
            requireCoordinates(courier.departureLocation(),
                    "Le lieu de départ du livreur " + courier.name() + " doit être confirmé sur la carte.");
        }
        for (DeliveryClient client : plan.clients()) {
            for (DeliveryGroup group : client.deliveryGroups()) {
                requireCoordinates(group.pickup(),
                        "Le retrait « " + group.name() + " » doit être confirmé sur la carte.");
            }
        }
    }

    private CollectionSetup collectionSetup(DeliveryPlan plan, List<HubCollectionTask> tasks) {
        List<RoutingPoint> points = new ArrayList<>();
        int[] startMatrixPoints = new int[plan.couriers().size()];
        for (int courierIndex = 0; courierIndex < plan.couriers().size(); courierIndex++) {
            Courier courier = plan.couriers().get(courierIndex);
            startMatrixPoints[courierIndex] = points.size();
            points.add(point("hub-start-" + courier.id(), courier.departureLocation()));
        }

        Map<String, Integer> pickupMatrixPoints = new LinkedHashMap<>();
        for (HubCollectionTask task : tasks) {
            pickupMatrixPoints.computeIfAbsent(task.deliveryGroupId(), ignored -> {
                int matrixPoint = points.size();
                points.add(point("hub-pickup-" + task.deliveryGroupId(), task.pickup()));
                return matrixPoint;
            });
        }
        int hubMatrixPoint = points.size();
        points.add(point("redistribution-hub", plan.redistributionHub()));
        RoutingMatrixResult matrix = routingService.matrix(points);

        List<Integer> matrixPointByNode = new ArrayList<>();
        int[] courierStartNodes = new int[plan.couriers().size()];
        for (int courierIndex = 0; courierIndex < plan.couriers().size(); courierIndex++) {
            courierStartNodes[courierIndex] = matrixPointByNode.size();
            matrixPointByNode.add(startMatrixPoints[courierIndex]);
        }
        int[] taskNodes = new int[tasks.size()];
        for (int taskIndex = 0; taskIndex < tasks.size(); taskIndex++) {
            taskNodes[taskIndex] = matrixPointByNode.size();
            matrixPointByNode.add(pickupMatrixPoints.get(tasks.get(taskIndex).deliveryGroupId()));
        }
        int hubNode = matrixPointByNode.size();
        matrixPointByNode.add(hubMatrixPoint);
        HubCollectionProblem problem = new HubCollectionProblem(
                plan.couriers(),
                tasks,
                hubNode,
                courierStartNodes,
                taskNodes,
                matrixPointByNode.stream().mapToInt(Integer::intValue).toArray(),
                matrix.distancesMeters());
        return new CollectionSetup(problem, matrix, startMatrixPoints, hubMatrixPoint);
    }

    private StabilizedDistribution stabilizeDistribution(
            DeliveryPlan plan,
            List<MultiClientOptimizationJob> jobs,
            CollectionSetup collectionSetup,
            HubCollectionSolvedOptimization collection,
            Map<String, HubCollectionSolvedOptimization.SolvedRoute> collectionRoutes,
            Map<String, CourierApproach> approaches) {
        Map<String, OffsetDateTime> arrivals = participantArrivals(
                plan,
                collection,
                collectionRoutes,
                approaches);
        OffsetDateTime collectorMeeting = latestArrival(collectionRoutes.keySet(), arrivals);
        if (jobs.isEmpty()) {
            return new StabilizedDistribution(
                    collectorMeeting,
                    true,
                    0,
                    null,
                    null,
                    List.of(),
                    arrivals,
                    0);
        }

        DistributionMatrix distributionMatrix = distributionMatrix(plan, jobs);
        List<Courier> candidates = plan.couriers().stream()
                .filter(courier -> collectionRoutes.containsKey(courier.id()) || approaches.containsKey(courier.id()))
                .toList();
        OffsetDateTime meeting = latestArrival(
                candidates.stream().map(Courier::id).collect(java.util.stream.Collectors.toSet()),
                arrivals);
        HubDistributionProblem finalProblem = null;
        HubDistributionSolvedOptimization finalSolved = null;
        List<Courier> finalEligible = List.of();
        boolean stable = false;
        long computationMillis = 0;
        int iterations = 0;

        for (int iteration = 0; iteration < MAX_MEETING_STABILIZATION_ITERATIONS; iteration++) {
            iterations = iteration + 1;
            OffsetDateTime iterationMeeting = meeting;
            List<Courier> eligible = candidates.stream()
                    .filter(courier -> !arrivals.get(courier.id()).toInstant().isAfter(iterationMeeting.toInstant()))
                    .toList();
            HubDistributionProblem problem = new HubDistributionProblem(
                    iterationMeeting.plus(operationalPolicy.hubTransferDuration()),
                    eligible,
                    jobs,
                    distributionMatrix.hubNode(),
                    distributionMatrix.deliveryNodes(),
                    distributionMatrix.matrix().distancesMeters(),
                    distributionActivationCosts(eligible, collectionRoutes, approaches));
            HubDistributionSolvedOptimization solved = distributionOptimizer.solve(problem);
            computationMillis = Math.addExact(computationMillis, solved.computationTimeMillis());
            Set<String> usedDistributors = solved.routes().stream()
                    .map(route -> problem.couriers().get(route.courierIndex()).id())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> participants = new LinkedHashSet<>(collectionRoutes.keySet());
            participants.addAll(usedDistributors);
            OffsetDateTime requiredMeeting = latestArrival(participants, arrivals);

            finalProblem = problem;
            finalSolved = solved;
            finalEligible = eligible;
            meeting = iterationMeeting;
            if (sameInstant(requiredMeeting, iterationMeeting)) {
                stable = true;
                break;
            }
            meeting = requiredMeeting;
        }
        return new StabilizedDistribution(
                finalProblem == null ? collectorMeeting : finalProblem.distributionDepartureAt()
                        .minus(operationalPolicy.hubTransferDuration()),
                stable,
                iterations,
                finalProblem,
                finalSolved,
                finalEligible,
                arrivals,
                computationMillis,
                distributionMatrix.matrix());
    }

    private long[] distributionActivationCosts(
            List<Courier> couriers,
            Map<String, HubCollectionSolvedOptimization.SolvedRoute> collectionRoutes,
            Map<String, CourierApproach> approaches) {
        long[] costs = new long[couriers.size()];
        for (int index = 0; index < couriers.size(); index++) {
            String courierId = couriers.get(index).id();
            if (!collectionRoutes.containsKey(courierId)) {
                CourierApproach approach = approaches.get(courierId);
                costs[index] = approach == null ? 0 : roundedDistance(approach.distanceMeters());
            }
        }
        return costs;
    }

    private DistributionMatrix distributionMatrix(DeliveryPlan plan, List<MultiClientOptimizationJob> jobs) {
        List<RoutingPoint> points = new ArrayList<>();
        points.add(point("distribution-hub", plan.redistributionHub()));
        int[] deliveryNodes = new int[jobs.size()];
        for (int jobIndex = 0; jobIndex < jobs.size(); jobIndex++) {
            deliveryNodes[jobIndex] = points.size();
            DeliveryPackage deliveryPackage = jobs.get(jobIndex).deliveryPackage();
            points.add(point("hub-delivery-" + deliveryPackage.id(), deliveryPackage.destination()));
        }
        return new DistributionMatrix(routingService.matrix(points), 0, deliveryNodes);
    }

    private Map<String, CourierApproach> approachesToHub(DeliveryPlan plan, CollectionSetup setup) {
        Map<String, CourierApproach> approaches = new LinkedHashMap<>();
        for (int courierIndex = 0; courierIndex < plan.couriers().size(); courierIndex++) {
            Courier courier = plan.couriers().get(courierIndex);
            Double distance = setup.matrix().distancesMeters()
                    .get(setup.startMatrixPoints()[courierIndex])
                    .get(setup.hubMatrixPoint());
            if (distance == null || !Double.isFinite(distance)) continue;
            double nonNegativeDistance = Math.max(0, distance);
            long travelSeconds = travelSeconds(nonNegativeDistance, courier);
            approaches.put(courier.id(), new CourierApproach(
                    nonNegativeDistance,
                    travelSeconds,
                    courier.departureAt().plusSeconds(travelSeconds)));
        }
        return Map.copyOf(approaches);
    }

    private Map<String, OffsetDateTime> participantArrivals(
            DeliveryPlan plan,
            HubCollectionSolvedOptimization collection,
            Map<String, HubCollectionSolvedOptimization.SolvedRoute> collectionRoutes,
            Map<String, CourierApproach> approaches) {
        Map<String, OffsetDateTime> arrivals = new HashMap<>();
        for (Courier courier : plan.couriers()) {
            HubCollectionSolvedOptimization.SolvedRoute collectionRoute = collectionRoutes.get(courier.id());
            if (collectionRoute != null) {
                arrivals.put(courier.id(), collection.timeReference().plusSeconds(collectionRoute.hubArrivalOffsetSeconds()));
            } else if (approaches.containsKey(courier.id())) {
                arrivals.put(courier.id(), approaches.get(courier.id()).arrivedAt());
            }
        }
        return Map.copyOf(arrivals);
    }

    private OffsetDateTime latestArrival(Set<String> courierIds, Map<String, OffsetDateTime> arrivals) {
        return courierIds.stream()
                .map(arrivals::get)
                .filter(Objects::nonNull)
                .max(Comparator.comparing(OffsetDateTime::toInstant))
                .orElse(null);
    }

    private Map<String, HubCollectionSolvedOptimization.SolvedRoute> collectionRoutesByCourier(
            HubCollectionProblem problem,
            HubCollectionSolvedOptimization solved) {
        Map<String, HubCollectionSolvedOptimization.SolvedRoute> routes = new LinkedHashMap<>();
        for (HubCollectionSolvedOptimization.SolvedRoute route : solved.routes()) {
            routes.put(problem.couriers().get(route.courierIndex()).id(), route);
        }
        return Map.copyOf(routes);
    }

    private Map<String, String> collectorsByPackage(
            HubCollectionProblem problem,
            HubCollectionSolvedOptimization solved) {
        Map<String, String> collectors = new LinkedHashMap<>();
        for (HubCollectionSolvedOptimization.SolvedRoute route : solved.routes()) {
            String courierId = problem.couriers().get(route.courierIndex()).id();
            for (HubCollectionSolvedOptimization.SolvedVisit visit : route.visits()) {
                for (DeliveryPackage deliveryPackage : problem.tasks().get(visit.taskIndex()).packages()) {
                    collectors.put(deliveryPackage.id(), courierId);
                }
            }
        }
        return Map.copyOf(collectors);
    }

    private Map<String, HubDistributionSolvedOptimization.SolvedRoute> distributionRoutesByCourier(
            StabilizedDistribution distribution) {
        if (distribution.problem() == null || distribution.solved() == null) return Map.of();
        Map<String, HubDistributionSolvedOptimization.SolvedRoute> routes = new LinkedHashMap<>();
        for (HubDistributionSolvedOptimization.SolvedRoute route : distribution.solved().routes()) {
            routes.put(distribution.problem().couriers().get(route.courierIndex()).id(), route);
        }
        return Map.copyOf(routes);
    }

    private Map<String, String> distributorsByPackage(StabilizedDistribution distribution) {
        if (distribution.problem() == null || distribution.solved() == null) return Map.of();
        Map<String, String> distributors = new LinkedHashMap<>();
        for (HubDistributionSolvedOptimization.SolvedRoute route : distribution.solved().routes()) {
            String courierId = distribution.problem().couriers().get(route.courierIndex()).id();
            for (HubDistributionSolvedOptimization.SolvedVisit visit : route.visits()) {
                distributors.put(distribution.problem().jobs().get(visit.jobIndex()).deliveryPackage().id(), courierId);
            }
        }
        return Map.copyOf(distributors);
    }

    private Map<String, UnassignedPackage> unassignedPackages(
            DeliveryPlan plan,
            List<MultiClientOptimizationJob> allJobs,
            Set<String> collectedPackageIds,
            StabilizedDistribution distribution,
            List<String> warnings) {
        BigDecimal maximumCapacity = plan.couriers().stream()
                .map(Courier::maxWeightKg)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        Map<String, UnassignedPackage> unassigned = new LinkedHashMap<>();
        for (MultiClientOptimizationJob job : allJobs) {
            DeliveryPackage deliveryPackage = job.deliveryPackage();
            if (!hasCoordinates(deliveryPackage.destination())) {
                unassigned.put(deliveryPackage.id(), unassigned(
                        job,
                        UnassignedReason.DESTINATION_UNRESOLVED,
                        "La destination doit être confirmée sur la carte.",
                        null));
                continue;
            }
            if (collectedPackageIds.contains(deliveryPackage.id())) continue;
            boolean overweight = deliveryPackage.weightKg().compareTo(maximumCapacity) > 0;
            unassigned.put(deliveryPackage.id(), unassigned(
                    job,
                    overweight ? UnassignedReason.OVERWEIGHT_FOR_ALL_COURIERS : UnassignedReason.NO_FEASIBLE_ASSIGNMENT,
                    overweight
                            ? "Aucun livreur ne peut transporter ce colis jusqu'au siège."
                            : "Ce colis n'a pas pu être collecté avant le rendez-vous.",
                    overweight ? deliveryPackage.weightKg() : null));
        }
        if (distribution.problem() != null && distribution.solved() != null) {
            for (Integer droppedJobIndex : distribution.solved().droppedJobIndexes()) {
                MultiClientOptimizationJob job = distribution.problem().jobs().get(droppedJobIndex);
                UnassignedReason reason = job.deliveryPackage().deliveryWindow() == null
                        ? UnassignedReason.NO_FEASIBLE_ASSIGNMENT
                        : UnassignedReason.TIME_WINDOW_UNSATISFIABLE;
                unassigned.putIfAbsent(job.deliveryPackage().id(), unassigned(
                        job,
                        reason,
                        reason == UnassignedReason.TIME_WINDOW_UNSATISFIABLE
                                ? "Aucune livraison via le siège ne respecte la tolérance du créneau."
                                : "Ce colis a été collecté mais aucune distribution n'a été trouvée.",
                        null));
            }
        }
        if (!unassigned.isEmpty()) {
            warnings.add(unassigned.size() + " colis n'ont pas pu terminer les deux phases via le siège.");
        }
        return Map.copyOf(unassigned);
    }

    private List<CourierRoute> buildCombinedRoutes(
            DeliveryPlan plan,
            HubCollectionProblem collectionProblem,
            HubCollectionSolvedOptimization collection,
            Map<String, HubCollectionSolvedOptimization.SolvedRoute> collectionRoutes,
            StabilizedDistribution distribution,
            Map<String, HubDistributionSolvedOptimization.SolvedRoute> distributionRoutes,
            Map<String, CourierApproach> approaches,
            Set<String> participantIds,
            OffsetDateTime redistributionCompletedAt) {
        Map<String, MultiClientOptimizationJob> jobsByPackage = jobs(plan).stream()
                .collect(java.util.stream.Collectors.toMap(
                        job -> job.deliveryPackage().id(),
                        job -> job,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<CourierRoute> routes = new ArrayList<>();
        for (Courier courier : plan.couriers()) {
            if (!participantIds.contains(courier.id())) continue;
            HubCollectionSolvedOptimization.SolvedRoute collectionRoute = collectionRoutes.get(courier.id());
            HubDistributionSolvedOptimization.SolvedRoute distributionRoute = distributionRoutes.get(courier.id());
            List<RouteStop> stops = new ArrayList<>();
            double lastVisibleDistance = 0;
            BigDecimal collectionLoad = BigDecimal.ZERO;

            if (collectionRoute != null) {
                for (HubCollectionSolvedOptimization.SolvedVisit visit : collectionRoute.visits()) {
                    HubCollectionTask task = collectionProblem.tasks().get(visit.taskIndex());
                    collectionLoad = collectionLoad.add(task.weightKg());
                    for (int packageIndex = 0; packageIndex < task.packages().size(); packageIndex++) {
                        DeliveryPackage deliveryPackage = task.packages().get(packageIndex);
                        MultiClientOptimizationJob job = jobsByPackage.get(deliveryPackage.id());
                        double cumulativeDistance = visit.cumulativeDistanceMeters();
                        stops.add(new RouteStop(
                                stops.size() + 1,
                                RouteStopType.PICKUP,
                                job.clientId(),
                                job.clientName(),
                                job.deliveryGroupId(),
                                job.deliveryGroupName(),
                                deliveryPackage.id(),
                                deliveryPackage.label(),
                                deliveryPackage.phone(),
                                deliveryPackage.weightKg(),
                                task.pickup(),
                                collection.timeReference().plusSeconds(visit.arrivalOffsetSeconds()),
                                packageIndex == 0 ? visit.distanceFromPreviousMeters() : 0,
                                packageIndex == 0 ? visit.travelDurationFromPreviousSeconds() : 0,
                                cumulativeDistance,
                                elapsedSeconds(courier.departureAt(),
                                        collection.timeReference().plusSeconds(visit.arrivalOffsetSeconds()))));
                        lastVisibleDistance = cumulativeDistance;
                    }
                }
            }

            double phaseOneDistance = collectionRoute != null
                    ? collectionRoute.distanceMeters()
                    : approaches.get(courier.id()).distanceMeters();
            BigDecimal distributionLoad = BigDecimal.ZERO;
            if (distributionRoute != null && distribution.problem() != null && distribution.solved() != null) {
                boolean firstDelivery = true;
                for (HubDistributionSolvedOptimization.SolvedVisit visit : distributionRoute.visits()) {
                    MultiClientOptimizationJob job = distribution.problem().jobs().get(visit.jobIndex());
                    DeliveryPackage deliveryPackage = job.deliveryPackage();
                    distributionLoad = distributionLoad.add(deliveryPackage.weightKg());
                    double cumulativeDistance = phaseOneDistance + visit.cumulativeDistanceMeters();
                    double distanceFromPrevious = Math.max(0, cumulativeDistance - lastVisibleDistance);
                    double hiddenTravel = firstDelivery
                            ? (collectionRoute != null
                                    ? travelSeconds(collectionRoute.hubLegDistanceMeters(), courier)
                                    : approaches.get(courier.id()).travelSeconds())
                            : 0;
                    OffsetDateTime serviceStarted = distribution.solved().timeReference()
                            .plusSeconds(visit.serviceStartedOffsetSeconds());
                    stops.add(new RouteStop(
                            stops.size() + 1,
                            RouteStopType.DELIVERY,
                            job.clientId(),
                            job.clientName(),
                            job.deliveryGroupId(),
                            job.deliveryGroupName(),
                            deliveryPackage.id(),
                            deliveryPackage.label(),
                            deliveryPackage.phone(),
                            deliveryPackage.weightKg(),
                            deliveryPackage.destination(),
                            serviceStarted,
                            distanceFromPrevious,
                            hiddenTravel + visit.travelDurationFromPreviousSeconds(),
                            cumulativeDistance,
                            elapsedSeconds(courier.departureAt(), serviceStarted)));
                    lastVisibleDistance = cumulativeDistance;
                    firstDelivery = false;
                }
            }

            BigDecimal peakLoad = collectionLoad.max(distributionLoad);
            BigDecimal remaining = courier.maxWeightKg().subtract(peakLoad);
            BigDecimal utilization = peakLoad.multiply(BigDecimal.valueOf(100))
                    .divide(courier.maxWeightKg(), 2, RoundingMode.HALF_UP);
            double distributionDistance = distributionRoute == null ? 0 : distributionRoute.distanceMeters();
            OffsetDateTime completedAt = distributionRoute == null
                    ? redistributionCompletedAt
                    : distribution.solved().timeReference().plusSeconds(distributionRoute.completionOffsetSeconds());
            routes.add(new CourierRoute(
                    courier.id(),
                    courier.name(),
                    courier.vehicleType(),
                    peakLoad,
                    courier.maxWeightKg(),
                    remaining,
                    utilization,
                    courier.departureLocation(),
                    courier.departureAt(),
                    completedAt,
                    phaseOneDistance + distributionDistance,
                    elapsedSeconds(courier.departureAt(), completedAt),
                    stops));
        }
        return List.copyOf(routes);
    }

    private OptimizationResult result(
            DeliveryPlan plan,
            int packageCount,
            List<CourierRoute> routes,
            List<UnassignedPackage> unassigned,
            List<String> warnings,
            RoutingMatrixResult collectionMatrix,
            HubCollectionSolvedOptimization collection,
            StabilizedDistribution distribution,
            long totalComputationMillis) {
        int assignedCount = packageCount - unassigned.size();
        BigDecimal assignedWeight = routes.stream()
                .flatMap(route -> route.stops().stream())
                .filter(stop -> stop.type() == RouteStopType.DELIVERY)
                .map(RouteStop::weightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double totalDistance = routes.stream().mapToDouble(CourierRoute::distanceMeters).sum();
        double totalDuration = routes.stream().mapToDouble(CourierRoute::durationSeconds).sum();
        OptimizationStatus status = assignedCount == 0 && packageCount > 0
                ? OptimizationStatus.NO_SOLUTION
                : unassigned.isEmpty() ? OptimizationStatus.SUCCESS : OptimizationStatus.SUCCESS_WITH_WARNINGS;
        String distributionStatus = distribution.solved() == null ? "NOT_RUN" : distribution.solved().solverStatus();
        return new OptimizationResult(
                RESULT_SCHEMA_VERSION,
                plan.planId(),
                OffsetDateTime.now(ANTANANARIVO),
                status,
                "COLLECTION_" + collection.solverStatus() + ";DISTRIBUTION_" + distributionStatus,
                totalComputationMillis,
                new DataContext(collectionMatrix.provider(), collectionMatrix.preset(), OPTIMIZER_NAME, OPTIMIZER_VERSION),
                new OptimizationSummary(
                        packageCount,
                        assignedCount,
                        unassigned.size(),
                        plan.couriers().size(),
                        routes.size(),
                        assignedWeight,
                        totalDistance,
                        totalDuration),
                routes,
                unassigned,
                warnings);
    }

    private HubExecution hubExecution(
            DeliveryPlan plan,
            List<MultiClientOptimizationJob> allJobs,
            HubCollectionProblem collectionProblem,
            HubCollectionSolvedOptimization collection,
            Map<String, HubCollectionSolvedOptimization.SolvedRoute> collectionRoutes,
            StabilizedDistribution distribution,
            Map<String, HubDistributionSolvedOptimization.SolvedRoute> distributionRoutes,
            Map<String, CourierApproach> approaches,
            Map<String, String> collectorByPackage,
            Map<String, String> distributorByPackage,
            Set<String> participantIds,
            OffsetDateTime redistributionCompletedAt) {
        Map<String, Courier> couriers = plan.couriers().stream()
                .collect(java.util.stream.Collectors.toMap(Courier::id, courier -> courier));
        Map<String, OffsetDateTime> collectorArrivals = new HashMap<>();
        for (Map.Entry<String, HubCollectionSolvedOptimization.SolvedRoute> entry : collectionRoutes.entrySet()) {
            collectorArrivals.put(entry.getKey(),
                    collection.timeReference().plusSeconds(entry.getValue().hubArrivalOffsetSeconds()));
        }
        Map<String, PackageReference> references = allJobs.stream()
                .collect(java.util.stream.Collectors.toMap(
                        job -> job.deliveryPackage().id(),
                        this::reference,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<CourierHubManifest> manifests = new ArrayList<>();
        for (Courier courier : plan.couriers()) {
            if (!participantIds.contains(courier.id())) continue;
            boolean collector = collectionRoutes.containsKey(courier.id());
            boolean distributor = distributionRoutes.containsKey(courier.id());
            OffsetDateTime arrivedAt = collector
                    ? collectorArrivals.get(courier.id())
                    : approaches.get(courier.id()).arrivedAt();
            List<PackageReference> collected = packageReferences(
                    allJobs, references, job -> courier.id().equals(collectorByPackage.get(job.deliveryPackage().id())));
            List<PackageReference> delivered = packageReferences(
                    allJobs, references, job -> courier.id().equals(distributorByPackage.get(job.deliveryPackage().id())));
            List<PackageReference> kept = packageReferences(allJobs, references, job -> {
                String packageId = job.deliveryPackage().id();
                return courier.id().equals(collectorByPackage.get(packageId))
                        && courier.id().equals(distributorByPackage.get(packageId));
            });
            List<PackageReference> transferredOut = packageReferences(allJobs, references, job -> {
                String packageId = job.deliveryPackage().id();
                String distributorId = distributorByPackage.get(packageId);
                return courier.id().equals(collectorByPackage.get(packageId))
                        && distributorId != null
                        && !courier.id().equals(distributorId);
            });
            List<PackageReference> received = packageReferences(allJobs, references, job -> {
                String packageId = job.deliveryPackage().id();
                String collectorId = collectorByPackage.get(packageId);
                return courier.id().equals(distributorByPackage.get(packageId))
                        && collectorId != null
                        && !courier.id().equals(collectorId);
            });
            manifests.add(new CourierHubManifest(
                    courier.id(),
                    courier.name(),
                    roles(collector, distributor),
                    arrivedAt,
                    redistributionCompletedAt,
                    waitingSeconds(arrivedAt, distribution.meetingAt()),
                    weight(collected),
                    weight(delivered),
                    collected,
                    kept,
                    transferredOut,
                    received,
                    delivered));
        }

        List<PackageTransfer> transfers = new ArrayList<>();
        for (MultiClientOptimizationJob job : allJobs) {
            String packageId = job.deliveryPackage().id();
            String collectorId = collectorByPackage.get(packageId);
            if (collectorId == null) continue;
            String distributorId = distributorByPackage.get(packageId);
            Courier collector = couriers.get(collectorId);
            Courier distributor = distributorId == null ? null : couriers.get(distributorId);
            transfers.add(new PackageTransfer(
                    references.get(packageId),
                    collector.id(),
                    collector.name(),
                    distributor == null ? null : distributor.id(),
                    distributor == null ? null : distributor.name(),
                    distributor != null && !collector.id().equals(distributor.id()),
                    collectorArrivals.get(collector.id()),
                    distributor == null ? null : redistributionCompletedAt));
        }

        long collectionDistance = roundedDistance(collection.routes().stream()
                .mapToDouble(HubCollectionSolvedOptimization.SolvedRoute::distanceMeters).sum());
        long approachDistance = roundedDistance(distributionRoutes.keySet().stream()
                .filter(courierId -> !collectionRoutes.containsKey(courierId))
                .map(approaches::get)
                .filter(Objects::nonNull)
                .mapToDouble(CourierApproach::distanceMeters)
                .sum());
        long distributionDistance = roundedDistance(distributionRoutes.values().stream()
                .mapToDouble(HubDistributionSolvedOptimization.SolvedRoute::distanceMeters).sum());
        long hubWaiting = manifests.stream().mapToLong(CourierHubManifest::waitingSeconds).sum();
        return new HubExecution(
                plan.redistributionHub(),
                distribution.meetingAt(),
                redistributionCompletedAt,
                collectionDistance,
                approachDistance,
                distributionDistance,
                hubWaiting,
                manifests,
                transfers);
    }

    private List<HubRole> roles(boolean collector, boolean distributor) {
        List<HubRole> roles = new ArrayList<>();
        if (collector) roles.add(HubRole.COLLECTION);
        if (distributor) roles.add(HubRole.DISTRIBUTION);
        return List.copyOf(roles);
    }

    private List<PackageReference> packageReferences(
            List<MultiClientOptimizationJob> jobs,
            Map<String, PackageReference> references,
            java.util.function.Predicate<MultiClientOptimizationJob> predicate) {
        return jobs.stream()
                .filter(predicate)
                .map(job -> references.get(job.deliveryPackage().id()))
                .toList();
    }

    private PackageReference reference(MultiClientOptimizationJob job) {
        DeliveryPackage deliveryPackage = job.deliveryPackage();
        return new PackageReference(
                deliveryPackage.id(),
                deliveryPackage.label(),
                deliveryPackage.weightKg(),
                job.clientId(),
                job.clientName(),
                job.deliveryGroupId(),
                job.deliveryGroupName());
    }

    private BigDecimal weight(List<PackageReference> packages) {
        return packages.stream().map(PackageReference::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long waitingSeconds(OffsetDateTime arrivedAt, OffsetDateTime meetingAt) {
        if (arrivedAt == null || meetingAt == null) return 0;
        return Math.max(0, Duration.between(arrivedAt.toInstant(), meetingAt.toInstant()).toSeconds());
    }

    private UnassignedPackage unassigned(
            MultiClientOptimizationJob job,
            UnassignedReason reason,
            String message,
            BigDecimal minimumRequiredCapacity) {
        DeliveryPackage deliveryPackage = job.deliveryPackage();
        return new UnassignedPackage(
                deliveryPackage.id(),
                deliveryPackage.label(),
                deliveryPackage.weightKg(),
                reason,
                message,
                minimumRequiredCapacity,
                job.clientId(),
                job.clientName(),
                job.deliveryGroupId(),
                job.deliveryGroupName());
    }

    private List<MultiClientOptimizationJob> jobs(DeliveryPlan plan) {
        List<MultiClientOptimizationJob> jobs = new ArrayList<>();
        for (DeliveryClient client : plan.clients()) {
            for (DeliveryGroup group : client.deliveryGroups()) {
                for (DeliveryPackage deliveryPackage : group.packages()) {
                    jobs.add(new MultiClientOptimizationJob(
                            client.id(), client.name(), group.id(), group.name(), group.pickup(), deliveryPackage));
                }
            }
        }
        return List.copyOf(jobs);
    }

    private HubOptimizationOutcome emptyOutcome(DeliveryPlan plan, List<MultiClientOptimizationJob> jobs) {
        List<UnassignedPackage> unassigned = jobs.stream()
                .map(job -> !hasCoordinates(job.deliveryPackage().destination())
                        ? unassigned(job, UnassignedReason.DESTINATION_UNRESOLVED,
                                "La destination doit être confirmée sur la carte.", null)
                        : unassigned(job, UnassignedReason.NO_FEASIBLE_ASSIGNMENT,
                                "Aucune collecte n'a été créée.", null))
                .toList();
        OptimizationResult result = new OptimizationResult(
                RESULT_SCHEMA_VERSION,
                plan.planId(),
                OffsetDateTime.now(ANTANANARIVO),
                unassigned.isEmpty() ? OptimizationStatus.SUCCESS : OptimizationStatus.NO_SOLUTION,
                "NOT_RUN",
                0,
                new DataContext("NOT_CALCULATED", plan.settings().routingPreset(), OPTIMIZER_NAME, OPTIMIZER_VERSION),
                new OptimizationSummary(jobs.size(), jobs.size() - unassigned.size(), unassigned.size(),
                        plan.couriers().size(), 0,
                        BigDecimal.ZERO, 0, 0),
                List.of(),
                unassigned,
                unassigned.isEmpty()
                        ? List.of()
                        : List.of(unassigned.size() + " colis n'ont pas pu être traités via le siège."));
        HubExecution execution = new HubExecution(
                plan.redistributionHub(), null, null, 0, 0, 0, 0, List.of(), List.of());
        return new HubOptimizationOutcome(
                result,
                execution,
                scenarioEvaluator.evaluate(plan, result, operationalPolicy));
    }

    private RoutingPoint point(String id, Location location) {
        return new RoutingPoint(id, location.latitude(), location.longitude());
    }

    private void requireCoordinates(Location location, String message) {
        if (location == null || location.latitude() == null || location.longitude() == null) {
            throw new OptimizationInputException(message);
        }
    }

    private boolean hasCoordinates(Location location) {
        return location != null && location.latitude() != null && location.longitude() != null;
    }

    private long travelSeconds(double distanceMeters, Courier courier) {
        return Math.max(0, Math.round(distanceMeters * 3.6 / courier.averageSpeedKmh().doubleValue()));
    }

    private long elapsedSeconds(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) return 0;
        return Math.max(0, Duration.between(from.toInstant(), to.toInstant()).toSeconds());
    }

    private long roundedDistance(double distance) {
        if (!Double.isFinite(distance)) throw new IllegalArgumentException("La distance doit être finie.");
        return Math.max(0, Math.round(distance));
    }

    private boolean sameInstant(OffsetDateTime first, OffsetDateTime second) {
        return first == null ? second == null : second != null && first.toInstant().equals(second.toInstant());
    }

    private record CollectionSetup(
            HubCollectionProblem problem,
            RoutingMatrixResult matrix,
            int[] startMatrixPoints,
            int hubMatrixPoint) {

        CollectionSetup {
            startMatrixPoints = startMatrixPoints.clone();
        }
    }

    private record PhaseSolution(
            CollectionSetup collectionSetup,
            HubCollectionSolvedOptimization collection,
            Map<String, HubCollectionSolvedOptimization.SolvedRoute> collectionRoutes,
            Map<String, String> collectorByPackage,
            Set<String> collectedPackageIds,
            Map<String, CourierApproach> approaches,
            StabilizedDistribution distribution) {

        PhaseSolution {
            collectionRoutes = Map.copyOf(collectionRoutes);
            collectorByPackage = Map.copyOf(collectorByPackage);
            collectedPackageIds = Set.copyOf(collectedPackageIds);
            approaches = Map.copyOf(approaches);
        }
    }

    private record DistributionMatrix(
            RoutingMatrixResult matrix,
            int hubNode,
            int[] deliveryNodes) {

        DistributionMatrix {
            deliveryNodes = deliveryNodes.clone();
        }
    }

    private record CourierApproach(
            double distanceMeters,
            long travelSeconds,
            OffsetDateTime arrivedAt) {
    }

    private record StabilizedDistribution(
            OffsetDateTime meetingAt,
            boolean stable,
            int iterations,
            HubDistributionProblem problem,
            HubDistributionSolvedOptimization solved,
            List<Courier> eligibleCouriers,
            Map<String, OffsetDateTime> participantArrivals,
            long computationTimeMillis,
            RoutingMatrixResult matrix) {

        StabilizedDistribution(
                OffsetDateTime meetingAt,
                boolean stable,
                int iterations,
                HubDistributionProblem problem,
                HubDistributionSolvedOptimization solved,
                List<Courier> eligibleCouriers,
                Map<String, OffsetDateTime> participantArrivals,
                long computationTimeMillis) {
            this(meetingAt, stable, iterations, problem, solved, eligibleCouriers,
                    participantArrivals, computationTimeMillis, null);
        }
    }
}
