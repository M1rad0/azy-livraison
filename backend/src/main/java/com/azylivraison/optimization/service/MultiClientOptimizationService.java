package com.azylivraison.optimization.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

@Service
class MultiClientOptimizationService {

    private static final String RESULT_SCHEMA_VERSION = "2.0";
    private static final String OPTIMIZER_NAME = "OR_TOOLS";
    private static final String OPTIMIZER_VERSION = "9.15.6755";
    private static final ZoneId ANTANANARIVO = ZoneId.of("Indian/Antananarivo");

    private final RoutingService routingService;
    private final MultiClientOrToolsRoutingOptimizer optimizer;

    MultiClientOptimizationService(
            RoutingService routingService,
            MultiClientOrToolsRoutingOptimizer optimizer) {
        this.routingService = routingService;
        this.optimizer = optimizer;
    }

    OptimizationResult optimize(DeliveryPlan plan) {
        List<MultiClientOptimizationJob> allJobs = jobs(plan);
        for (DeliveryClient client : plan.clients()) {
            for (DeliveryGroup group : client.deliveryGroups()) {
                requireCoordinates(group.pickup(),
                        "Le point de récupération « " + group.name() + " » doit être confirmé sur la carte.");
            }
        }

        List<String> warnings = new ArrayList<>();
        List<Courier> activeCouriers = activeCouriers(plan.couriers(), warnings);
        BigDecimal maximumCapacity = activeCouriers.stream()
                .map(Courier::maxWeightKg)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        List<MultiClientOptimizationJob> eligibleJobs = new ArrayList<>();
        List<UnassignedPackage> preliminaryUnassigned = new ArrayList<>();
        for (MultiClientOptimizationJob job : allJobs) {
            DeliveryPackage deliveryPackage = job.deliveryPackage();
            if (!hasCoordinates(deliveryPackage.destination())) {
                preliminaryUnassigned.add(unassigned(job, UnassignedReason.DESTINATION_UNRESOLVED,
                        "La destination doit être confirmée sur la carte.", null));
            } else if (deliveryPackage.weightKg().compareTo(maximumCapacity) > 0) {
                preliminaryUnassigned.add(unassigned(job, UnassignedReason.OVERWEIGHT_FOR_ALL_COURIERS,
                        "Aucun livreur disponible ne peut porter ce colis.", deliveryPackage.weightKg()));
            } else {
                eligibleJobs.add(job);
            }
        }

        if (activeCouriers.isEmpty()) {
            for (MultiClientOptimizationJob job : eligibleJobs) {
                preliminaryUnassigned.add(unassigned(job, UnassignedReason.NO_FEASIBLE_ASSIGNMENT,
                        "Aucun livreur ne possède un lieu de départ exploitable.", null));
            }
            return resultWithoutSolver(plan, allJobs.size(), preliminaryUnassigned, warnings, "NO_ACTIVE_COURIER");
        }
        if (eligibleJobs.isEmpty()) {
            return resultWithoutSolver(plan, allJobs.size(), preliminaryUnassigned, warnings, "NOT_RUN");
        }

        MatrixAndNodes matrixAndNodes = matrixAndNodes(activeCouriers, eligibleJobs);
        RoutingMatrixResult matrix = routingService.matrix(matrixAndNodes.points());
        MultiClientOptimizationProblem problem = new MultiClientOptimizationProblem(
                plan.departureAt(),
                activeCouriers,
                eligibleJobs,
                matrixAndNodes.courierStartNodes(),
                matrixAndNodes.pickupNodes(),
                matrixAndNodes.deliveryNodes(),
                matrixAndNodes.matrixPointByNode(),
                matrix.distancesMeters());
        MultiClientSolvedOptimization solved = optimizer.solve(problem);

        List<CourierRoute> routes = buildRoutes(problem, solved);
        List<UnassignedPackage> unassigned = new ArrayList<>(preliminaryUnassigned);
        for (Integer jobIndex : solved.droppedJobIndexes()) {
            MultiClientOptimizationJob job = eligibleJobs.get(jobIndex);
            UnassignedReason reason = job.deliveryPackage().deliveryWindow() == null
                    ? UnassignedReason.NO_FEASIBLE_ASSIGNMENT
                    : UnassignedReason.TIME_WINDOW_UNSATISFIABLE;
            unassigned.add(unassigned(job, reason,
                    reason == UnassignedReason.TIME_WINDOW_UNSATISFIABLE
                            ? "Aucune tournée ne respecte le créneau demandé."
                            : "Aucune affectation respectant les retraits et capacités n'a été trouvée.",
                    null));
        }

        BigDecimal assignedWeight = routes.stream()
                .flatMap(route -> route.stops().stream())
                .filter(stop -> stop.type() == RouteStopType.DELIVERY)
                .map(RouteStop::weightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double totalDistance = routes.stream().mapToDouble(CourierRoute::distanceMeters).sum();
        double totalDuration = routes.stream().mapToDouble(CourierRoute::durationSeconds).sum();
        OptimizationStatus status = routes.isEmpty() && !eligibleJobs.isEmpty() && solved.solverStatus().contains("FAIL")
                ? OptimizationStatus.NO_SOLUTION
                : unassigned.isEmpty() ? OptimizationStatus.SUCCESS : OptimizationStatus.SUCCESS_WITH_WARNINGS;
        return new OptimizationResult(
                RESULT_SCHEMA_VERSION,
                plan.planId(),
                OffsetDateTime.now(ANTANANARIVO),
                status,
                solved.solverStatus(),
                solved.computationTimeMillis(),
                new DataContext(matrix.provider(), matrix.preset(), OPTIMIZER_NAME, OPTIMIZER_VERSION),
                new OptimizationSummary(
                        allJobs.size(),
                        allJobs.size() - unassigned.size(),
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

    private MatrixAndNodes matrixAndNodes(
            List<Courier> couriers,
            List<MultiClientOptimizationJob> jobs) {
        List<RoutingPoint> points = new ArrayList<>();
        List<Integer> nodeMappings = new ArrayList<>();
        int[] courierStartNodes = new int[couriers.size()];
        java.util.Arrays.fill(courierStartNodes, -1);
        for (int courierIndex = 0; courierIndex < couriers.size(); courierIndex++) {
            Courier courier = couriers.get(courierIndex);
            if (courier.departureLocation() != null) {
                int matrixPoint = points.size();
                points.add(point("start-" + courier.id(), courier.departureLocation()));
                courierStartNodes[courierIndex] = nodeMappings.size();
                nodeMappings.add(matrixPoint);
            }
        }

        Map<String, Integer> pickupMatrixPoints = new HashMap<>();
        for (MultiClientOptimizationJob job : jobs) {
            pickupMatrixPoints.computeIfAbsent(job.deliveryGroupId(), ignored -> {
                int matrixPoint = points.size();
                points.add(point("pickup-" + job.deliveryGroupId(), job.pickup()));
                return matrixPoint;
            });
        }
        int[] deliveryMatrixPoints = new int[jobs.size()];
        for (int jobIndex = 0; jobIndex < jobs.size(); jobIndex++) {
            deliveryMatrixPoints[jobIndex] = points.size();
            MultiClientOptimizationJob job = jobs.get(jobIndex);
            points.add(point("delivery-" + job.deliveryPackage().id(), job.deliveryPackage().destination()));
        }

        int[] pickupNodes = new int[jobs.size()];
        int[] deliveryNodes = new int[jobs.size()];
        for (int jobIndex = 0; jobIndex < jobs.size(); jobIndex++) {
            pickupNodes[jobIndex] = nodeMappings.size();
            nodeMappings.add(pickupMatrixPoints.get(jobs.get(jobIndex).deliveryGroupId()));
            deliveryNodes[jobIndex] = nodeMappings.size();
            nodeMappings.add(deliveryMatrixPoints[jobIndex]);
        }
        return new MatrixAndNodes(
                List.copyOf(points),
                courierStartNodes,
                pickupNodes,
                deliveryNodes,
                nodeMappings.stream().mapToInt(Integer::intValue).toArray());
    }

    private List<CourierRoute> buildRoutes(
            MultiClientOptimizationProblem problem,
            MultiClientSolvedOptimization solved) {
        List<CourierRoute> routes = new ArrayList<>();
        for (MultiClientSolvedOptimization.SolvedRoute solvedRoute : solved.routes()) {
            Courier courier = problem.couriers().get(solvedRoute.courierIndex());
            List<RouteStop> stops = new ArrayList<>();
            BigDecimal currentLoad = BigDecimal.ZERO;
            BigDecimal peakLoad = BigDecimal.ZERO;
            for (int order = 0; order < solvedRoute.visits().size(); order++) {
                MultiClientSolvedOptimization.SolvedVisit visit = solvedRoute.visits().get(order);
                MultiClientOptimizationJob job = problem.jobs().get(visit.jobIndex());
                DeliveryPackage deliveryPackage = job.deliveryPackage();
                if (visit.type() == RouteStopType.PICKUP) {
                    currentLoad = currentLoad.add(deliveryPackage.weightKg());
                    peakLoad = peakLoad.max(currentLoad);
                } else {
                    currentLoad = currentLoad.subtract(deliveryPackage.weightKg());
                }
                Location location = visit.type() == RouteStopType.PICKUP ? job.pickup() : deliveryPackage.destination();
                stops.add(new RouteStop(
                        order + 1,
                        visit.type(),
                        job.clientId(),
                        job.clientName(),
                        job.deliveryGroupId(),
                        job.deliveryGroupName(),
                        deliveryPackage.id(),
                        deliveryPackage.label(),
                        deliveryPackage.phone(),
                        deliveryPackage.weightKg(),
                        location,
                        solved.timeReference().plusSeconds(visit.arrivalOffsetSeconds()),
                        visit.distanceFromPreviousMeters(),
                        visit.travelDurationFromPreviousSeconds(),
                        visit.cumulativeDistanceMeters(),
                        visit.arrivalOffsetSeconds() - solvedRoute.departureOffsetSeconds()));
            }
            BigDecimal remaining = courier.maxWeightKg().subtract(peakLoad);
            BigDecimal utilization = peakLoad.multiply(BigDecimal.valueOf(100))
                    .divide(courier.maxWeightKg(), 2, RoundingMode.HALF_UP);
            Location departureLocation = courier.departureLocation() != null
                    ? courier.departureLocation()
                    : stops.get(0).destination();
            routes.add(new CourierRoute(
                    courier.id(),
                    courier.name(),
                    courier.vehicleType(),
                    peakLoad,
                    courier.maxWeightKg(),
                    remaining,
                    utilization,
                    departureLocation,
                    solved.timeReference().plusSeconds(solvedRoute.departureOffsetSeconds()),
                    solved.timeReference().plusSeconds(solvedRoute.completionOffsetSeconds()),
                    solvedRoute.distanceMeters(),
                    solvedRoute.completionOffsetSeconds() - solvedRoute.departureOffsetSeconds(),
                    stops));
        }
        return List.copyOf(routes);
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

    private List<Courier> activeCouriers(List<Courier> couriers, List<String> warnings) {
        List<Courier> active = new ArrayList<>();
        for (Courier courier : couriers) {
            if (courier.departureLocation() != null && !hasCoordinates(courier.departureLocation())) {
                warnings.add("Le lieu de départ du livreur " + courier.name()
                        + " n'est pas résolu ; ce livreur est exclu du calcul.");
            } else {
                active.add(courier);
            }
        }
        return List.copyOf(active);
    }

    private OptimizationResult resultWithoutSolver(
            DeliveryPlan plan,
            int packageCount,
            List<UnassignedPackage> unassigned,
            List<String> warnings,
            String solverStatus) {
        return new OptimizationResult(
                RESULT_SCHEMA_VERSION,
                plan.planId(),
                OffsetDateTime.now(ANTANANARIVO),
                unassigned.isEmpty() ? OptimizationStatus.SUCCESS : OptimizationStatus.SUCCESS_WITH_WARNINGS,
                solverStatus,
                0,
                new DataContext("NOT_CALCULATED", plan.settings().routingPreset(), OPTIMIZER_NAME, OPTIMIZER_VERSION),
                new OptimizationSummary(packageCount, packageCount - unassigned.size(), unassigned.size(),
                        plan.couriers().size(), 0, BigDecimal.ZERO, 0, 0),
                List.of(),
                unassigned,
                warnings);
    }

    private UnassignedPackage unassigned(
            MultiClientOptimizationJob job,
            UnassignedReason reason,
            String message,
            BigDecimal minimumRequiredCapacity) {
        DeliveryPackage deliveryPackage = job.deliveryPackage();
        return new UnassignedPackage(
                deliveryPackage.id(), deliveryPackage.label(), deliveryPackage.weightKg(), reason, message,
                minimumRequiredCapacity, job.clientId(), job.clientName(), job.deliveryGroupId(), job.deliveryGroupName());
    }

    private RoutingPoint point(String id, Location location) {
        return new RoutingPoint(id, location.latitude(), location.longitude());
    }

    private void requireCoordinates(Location location, String message) {
        if (!hasCoordinates(location)) throw new OptimizationInputException(message);
    }

    private boolean hasCoordinates(Location location) {
        return location != null && location.latitude() != null && location.longitude() != null;
    }

    private record MatrixAndNodes(
            List<RoutingPoint> points,
            int[] courierStartNodes,
            int[] pickupNodes,
            int[] deliveryNodes,
            int[] matrixPointByNode) {
    }
}
