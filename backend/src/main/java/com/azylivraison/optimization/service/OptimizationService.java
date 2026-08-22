package com.azylivraison.optimization.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.azylivraison.optimization.domain.OptimizationResult;
import com.azylivraison.optimization.domain.OptimizationResult.CourierRoute;
import com.azylivraison.optimization.domain.OptimizationResult.DataContext;
import com.azylivraison.optimization.domain.OptimizationResult.OptimizationStatus;
import com.azylivraison.optimization.domain.OptimizationResult.OptimizationSummary;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStop;
import com.azylivraison.optimization.domain.OptimizationResult.UnassignedPackage;
import com.azylivraison.optimization.domain.OptimizationResult.UnassignedReason;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.DeliveryPlan;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.PlanningPattern;
import com.azylivraison.routing.domain.RoutingPoint;
import com.azylivraison.routing.service.RoutingMatrixResult;
import com.azylivraison.routing.service.RoutingService;

import org.springframework.stereotype.Service;

@Service
public class OptimizationService {

    private static final String RESULT_SCHEMA_VERSION = "1.0";
    private static final String OPTIMIZER_NAME = "OR_TOOLS";
    private static final String OPTIMIZER_VERSION = "9.15.6755";
    private static final ZoneId ANTANANARIVO = ZoneId.of("Indian/Antananarivo");

    private final RoutingService routingService;
    private final OrToolsRoutingOptimizer optimizer;
    private final MultiClientOptimizationService multiClientOptimizationService;

    public OptimizationService(
            RoutingService routingService,
            OrToolsRoutingOptimizer optimizer,
            MultiClientOptimizationService multiClientOptimizationService) {
        this.routingService = routingService;
        this.optimizer = optimizer;
        this.multiClientOptimizationService = multiClientOptimizationService;
    }

    public OptimizationResult optimize(DeliveryPlan plan) {
        if (plan.pattern() == PlanningPattern.MULTI_CLIENT_MULTI_PICKUP_DELIVERY) {
            return multiClientOptimizationService.optimize(plan);
        }
        requireCoordinates(plan.depot(), "Le point de récupération doit être confirmé sur la carte.");

        List<String> warnings = new ArrayList<>();
        List<UnassignedPackage> preliminaryUnassigned = new ArrayList<>();
        List<Courier> activeCouriers = activeCouriers(plan, warnings);
        BigDecimal maximumCapacity = activeCouriers.stream()
                .map(Courier::maxWeightKg)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        List<DeliveryPackage> eligiblePackages = new ArrayList<>();

        for (DeliveryPackage deliveryPackage : plan.packages()) {
            if (!hasCoordinates(deliveryPackage.destination())) {
                preliminaryUnassigned.add(unassigned(
                        deliveryPackage,
                        UnassignedReason.DESTINATION_UNRESOLVED,
                        "La destination doit être confirmée sur la carte.",
                        null));
            } else if (deliveryPackage.weightKg().compareTo(maximumCapacity) > 0) {
                preliminaryUnassigned.add(unassigned(
                        deliveryPackage,
                        UnassignedReason.OVERWEIGHT_FOR_ALL_COURIERS,
                        "Aucun livreur disponible ne peut porter ce colis.",
                        deliveryPackage.weightKg()));
            } else {
                eligiblePackages.add(deliveryPackage);
            }
        }

        if (activeCouriers.isEmpty()) {
            for (DeliveryPackage deliveryPackage : eligiblePackages) {
                preliminaryUnassigned.add(unassigned(
                        deliveryPackage,
                        UnassignedReason.NO_FEASIBLE_ASSIGNMENT,
                        "Aucun livreur ne possède un lieu de départ exploitable.",
                        null));
            }
            return resultWithoutSolver(plan, preliminaryUnassigned, warnings, "NO_ACTIVE_COURIER");
        }
        if (eligiblePackages.isEmpty()) {
            return resultWithoutSolver(plan, preliminaryUnassigned, warnings, "NOT_RUN");
        }

        List<RoutingPoint> points = new ArrayList<>();
        points.add(point("depot", plan.depot()));
        int[] courierStartNodes = new int[activeCouriers.size()];
        for (int index = 0; index < activeCouriers.size(); index++) {
            Courier courier = activeCouriers.get(index);
            if (courier.departureLocation() == null) {
                courierStartNodes[index] = 0;
            } else {
                courierStartNodes[index] = points.size();
                points.add(point("start-" + courier.id(), courier.departureLocation()));
            }
        }
        int[] packageNodes = new int[eligiblePackages.size()];
        for (int index = 0; index < eligiblePackages.size(); index++) {
            packageNodes[index] = points.size();
            points.add(point("package-" + eligiblePackages.get(index).id(), eligiblePackages.get(index).destination()));
        }

        RoutingMatrixResult matrix = routingService.matrix(points);
        List<Courier> couriersReachingDepot = new ArrayList<>();
        List<Integer> reachableStartNodes = new ArrayList<>();
        for (int index = 0; index < activeCouriers.size(); index++) {
            Courier courier = activeCouriers.get(index);
            int startNode = courierStartNodes[index];
            if (startNode != 0 && matrix.distancesMeters().get(startNode).get(0) == null) {
                warnings.add("Le livreur " + courier.name()
                        + " ne peut pas rejoindre le point de récupération par la route ; il est exclu du calcul.");
            } else {
                couriersReachingDepot.add(courier);
                reachableStartNodes.add(startNode);
            }
        }
        activeCouriers = List.copyOf(couriersReachingDepot);
        courierStartNodes = reachableStartNodes.stream().mapToInt(Integer::intValue).toArray();
        if (activeCouriers.isEmpty()) {
            for (DeliveryPackage deliveryPackage : eligiblePackages) {
                preliminaryUnassigned.add(unassigned(
                        deliveryPackage,
                        UnassignedReason.NO_FEASIBLE_ASSIGNMENT,
                        "Aucun livreur ne peut rejoindre le point de récupération.",
                        null));
            }
            return resultWithoutSolver(plan, preliminaryUnassigned, warnings, "NO_COURIER_REACHES_DEPOT");
        }
        double[] courierApproachDistances = new double[courierStartNodes.length];
        for (int index = 0; index < courierStartNodes.length; index++) {
            int startNode = courierStartNodes[index];
            courierApproachDistances[index] = startNode == 0
                    ? 0
                    : matrix.distancesMeters().get(startNode).get(0);
        }
        OptimizationProblem problem = new OptimizationProblem(
                plan.departureAt(),
                plan.settings().returnToDepot(),
                0,
                activeCouriers,
                eligiblePackages,
                courierStartNodes,
                courierApproachDistances,
                packageNodes,
                matrix.distancesMeters());
        SolvedOptimization solved = optimizer.solve(problem);

        List<CourierRoute> routes = buildRoutes(plan, problem, solved);
        List<UnassignedPackage> allUnassigned = new ArrayList<>(preliminaryUnassigned);
        BigDecimal eligibleWeight = eligiblePackages.stream()
                .map(DeliveryPackage::weightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCapacity = activeCouriers.stream()
                .map(Courier::maxWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Integer packageIndex : solved.droppedPackageIndexes()) {
            DeliveryPackage deliveryPackage = eligiblePackages.get(packageIndex);
            UnassignedReason reason = droppedReason(
                    problem,
                    matrix,
                    packageIndex,
                    eligibleWeight.compareTo(totalCapacity) > 0,
                    solved.solverStatus());
            allUnassigned.add(unassigned(
                    deliveryPackage,
                    reason,
                    reasonMessage(reason),
                    reason == UnassignedReason.OVERWEIGHT_FOR_ALL_COURIERS ? deliveryPackage.weightKg() : null));
        }

        BigDecimal assignedWeight = routes.stream()
                .map(CourierRoute::loadedWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double totalDistance = routes.stream().mapToDouble(CourierRoute::distanceMeters).sum();
        double totalDuration = routes.stream().mapToDouble(CourierRoute::durationSeconds).sum();
        OptimizationStatus status = routes.isEmpty() && !eligiblePackages.isEmpty()
                && solved.solverStatus().contains("FAIL")
                        ? OptimizationStatus.NO_SOLUTION
                        : allUnassigned.isEmpty() ? OptimizationStatus.SUCCESS : OptimizationStatus.SUCCESS_WITH_WARNINGS;
        return new OptimizationResult(
                RESULT_SCHEMA_VERSION,
                plan.planId(),
                OffsetDateTime.now(ANTANANARIVO),
                status,
                solved.solverStatus(),
                solved.computationTimeMillis(),
                new DataContext(matrix.provider(), matrix.preset(), OPTIMIZER_NAME, OPTIMIZER_VERSION),
                new OptimizationSummary(
                        plan.packages().size(),
                        plan.packages().size() - allUnassigned.size(),
                        allUnassigned.size(),
                        plan.couriers().size(),
                        routes.size(),
                        assignedWeight,
                        totalDistance,
                        totalDuration),
                routes,
                allUnassigned,
                warnings);
    }

    private List<Courier> activeCouriers(DeliveryPlan plan, List<String> warnings) {
        List<Courier> active = new ArrayList<>();
        for (Courier courier : plan.couriers()) {
            if (courier.departureLocation() != null && !hasCoordinates(courier.departureLocation())) {
                warnings.add("Le lieu de départ du livreur " + courier.name()
                        + " n'est pas résolu ; ce livreur est exclu du calcul.");
            } else {
                active.add(courier);
            }
        }
        return List.copyOf(active);
    }

    private List<CourierRoute> buildRoutes(
            DeliveryPlan plan,
            OptimizationProblem problem,
            SolvedOptimization solved) {
        List<CourierRoute> routes = new ArrayList<>();
        for (SolvedOptimization.SolvedRoute solvedRoute : solved.routes()) {
            Courier courier = problem.couriers().get(solvedRoute.courierIndex());
            List<RouteStop> stops = new ArrayList<>();
            BigDecimal loadedWeight = BigDecimal.ZERO;
            for (int order = 0; order < solvedRoute.visits().size(); order++) {
                SolvedOptimization.SolvedVisit visit = solvedRoute.visits().get(order);
                DeliveryPackage deliveryPackage = problem.packages().get(visit.packageIndex());
                loadedWeight = loadedWeight.add(deliveryPackage.weightKg());
                stops.add(new RouteStop(
                        order + 1,
                        deliveryPackage.id(),
                        deliveryPackage.label(),
                        deliveryPackage.phone(),
                        deliveryPackage.weightKg(),
                        deliveryPackage.destination(),
                        solved.timeReference().plusSeconds(visit.arrivalOffsetSeconds()),
                        visit.distanceFromPreviousMeters(),
                        visit.travelDurationFromPreviousSeconds(),
                        visit.cumulativeDistanceMeters(),
                        visit.arrivalOffsetSeconds() - solvedRoute.departureOffsetSeconds()));
            }
            BigDecimal remaining = courier.maxWeightKg().subtract(loadedWeight);
            BigDecimal utilization = loadedWeight
                    .multiply(BigDecimal.valueOf(100))
                    .divide(courier.maxWeightKg(), 2, RoundingMode.HALF_UP);
            routes.add(new CourierRoute(
                    courier.id(),
                    courier.name(),
                    courier.vehicleType(),
                    loadedWeight,
                    courier.maxWeightKg(),
                    remaining,
                    utilization,
                    courier.departureLocation() == null ? plan.depot() : courier.departureLocation(),
                    solved.timeReference().plusSeconds(solvedRoute.departureOffsetSeconds()),
                    solved.timeReference().plusSeconds(solvedRoute.completionOffsetSeconds()),
                    solvedRoute.distanceMeters(),
                    solvedRoute.completionOffsetSeconds() - solvedRoute.departureOffsetSeconds(),
                    stops));
        }
        return List.copyOf(routes);
    }

    private UnassignedReason droppedReason(
            OptimizationProblem problem,
            RoutingMatrixResult matrix,
            int packageIndex,
            boolean totalCapacityInsufficient,
            String solverStatus) {
        int node = problem.packageNodes()[packageIndex];
        boolean reachable = false;
        for (int from = 0; from < matrix.distancesMeters().size(); from++) {
            if (from != node && matrix.distancesMeters().get(from).get(node) != null) {
                reachable = true;
                break;
            }
        }
        if (!reachable) return UnassignedReason.NO_ROAD_ROUTE;
        if (totalCapacityInsufficient) return UnassignedReason.INSUFFICIENT_TOTAL_CAPACITY;
        if (problem.packages().get(packageIndex).deliveryWindow() != null) {
            return UnassignedReason.TIME_WINDOW_UNSATISFIABLE;
        }
        if (solverStatus.contains("TIMEOUT")) {
            return UnassignedReason.SOLVER_TIMEOUT;
        }
        return UnassignedReason.NO_FEASIBLE_ASSIGNMENT;
    }

    private String reasonMessage(UnassignedReason reason) {
        return switch (reason) {
            case DESTINATION_UNRESOLVED -> "La destination n'est pas résolue.";
            case NO_ROAD_ROUTE -> "Aucun trajet routier ne permet d'atteindre cette destination.";
            case OVERWEIGHT_FOR_ALL_COURIERS -> "Le colis dépasse la capacité de tous les livreurs.";
            case INSUFFICIENT_TOTAL_CAPACITY -> "La capacité totale disponible est insuffisante.";
            case TIME_WINDOW_UNSATISFIABLE -> "Aucune tournée ne respecte le créneau demandé.";
            case SOLVER_TIMEOUT -> "Le solveur n'a pas trouvé d'affectation dans le temps imparti.";
            case NO_FEASIBLE_ASSIGNMENT -> "Aucune affectation respectant toutes les contraintes n'a été trouvée.";
        };
    }

    private OptimizationResult resultWithoutSolver(
            DeliveryPlan plan,
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
                new OptimizationSummary(
                        plan.packages().size(),
                        plan.packages().size() - unassigned.size(),
                        unassigned.size(),
                        plan.couriers().size(),
                        0,
                        BigDecimal.ZERO,
                        0,
                        0),
                List.of(),
                unassigned,
                warnings);
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

    private UnassignedPackage unassigned(
            DeliveryPackage deliveryPackage,
            UnassignedReason reason,
            String message,
            BigDecimal minimumRequiredCapacity) {
        return new UnassignedPackage(
                deliveryPackage.id(),
                deliveryPackage.label(),
                deliveryPackage.weightKg(),
                reason,
                message,
                minimumRequiredCapacity);
    }
}
