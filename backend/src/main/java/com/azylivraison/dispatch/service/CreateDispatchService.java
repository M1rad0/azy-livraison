package com.azylivraison.dispatch.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.azylivraison.administration.domain.ConfirmedLocation;
import com.azylivraison.administration.domain.CourierProfile;
import com.azylivraison.administration.domain.OperationalSettings;
import com.azylivraison.administration.domain.VehiclePresetConfiguration;
import com.azylivraison.administration.repository.CourierProfileRepository;
import com.azylivraison.administration.repository.OperationalSettingsRepository;
import com.azylivraison.administration.repository.VehiclePresetConfigurationRepository;
import com.azylivraison.dispatch.domain.CreateDispatchCommand;
import com.azylivraison.dispatch.domain.DispatchCreationResult;
import com.azylivraison.dispatch.domain.DispatchCourierResult;
import com.azylivraison.dispatch.domain.DispatchPackageOutcome;
import com.azylivraison.dispatch.domain.DispatchPlanDetails;
import com.azylivraison.dispatch.domain.DispatchPlanRecord;
import com.azylivraison.dispatch.domain.DispatchSourcePackage;
import com.azylivraison.dispatch.domain.DispatchStatus;
import com.azylivraison.dispatch.domain.DispatchStop;
import com.azylivraison.dispatch.domain.SelectedCourierSnapshot;
import com.azylivraison.dispatch.repository.DispatchPlanRepository;
import com.azylivraison.dispatch.repository.DispatchSourceRepository;
import com.azylivraison.optimization.domain.OperationalPolicy;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.CourierHubManifest;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.HubExecution;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.HubRole;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OperationalPolicySnapshot;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.PackageTransfer;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.StrategyMetrics;
import com.azylivraison.optimization.domain.OptimizationResult;
import com.azylivraison.optimization.domain.OptimizationResult.CourierRoute;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStop;
import com.azylivraison.optimization.domain.OptimizationResult.RouteStopType;
import com.azylivraison.optimization.domain.OptimizationResult.UnassignedPackage;
import com.azylivraison.optimization.domain.ViaHubOptimizationResult;
import com.azylivraison.optimization.domain.ViaHubRunPolicy;
import com.azylivraison.optimization.service.ViaHubOptimizationFacade;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryClient;
import com.azylivraison.planning.domain.DeliveryGroup;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.DeliveryPlan;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.PlanSettings;
import com.azylivraison.planning.domain.PlanningPattern;
import com.azylivraison.routing.config.RoutingProperties;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

/** Claims a queue snapshot, runs the hub strategy outside SQL transactions, then persists the schedule. */
@Service
public class CreateDispatchService {

    private static final ZoneId ANTANANARIVO = ZoneId.of("Indian/Antananarivo");
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    private static final int MAX_OPTIMIZATION_TIME_LIMIT_SECONDS = 1_200;
    private static final Duration STALE_DISPATCH_TIMEOUT = Duration.ofMinutes(30);

    private final DispatchPlanRepository dispatchPlanRepository;
    private final DispatchSourceRepository dispatchSourceRepository;
    private final OperationalSettingsRepository settingsRepository;
    private final CourierProfileRepository courierRepository;
    private final VehiclePresetConfigurationRepository presetRepository;
    private final ViaHubOptimizationFacade optimizationFacade;
    private final RoutingProperties routingProperties;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public CreateDispatchService(
            DispatchPlanRepository dispatchPlanRepository,
            DispatchSourceRepository dispatchSourceRepository,
            OperationalSettingsRepository settingsRepository,
            CourierProfileRepository courierRepository,
            VehiclePresetConfigurationRepository presetRepository,
            ViaHubOptimizationFacade optimizationFacade,
            RoutingProperties routingProperties,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper) {
        this.dispatchPlanRepository = dispatchPlanRepository;
        this.dispatchSourceRepository = dispatchSourceRepository;
        this.settingsRepository = settingsRepository;
        this.courierRepository = courierRepository;
        this.presetRepository = presetRepository;
        this.optimizationFacade = optimizationFacade;
        this.routingProperties = routingProperties;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public DispatchCreationResult create(CreateDispatchCommand command) {
        validateCommand(command);
        String requestHash = requestHash(command);
        ClaimResult claimResult;
        try {
            claimResult = Objects.requireNonNull(
                    transactionTemplate.execute(status -> claimOrReplay(command, requestHash)));
        } catch (DuplicateKeyException exception) {
            DispatchPlanRecord raced = dispatchPlanRepository
                    .findByActorAndIdempotencyKey(command.actorAccountId(), command.idempotencyKey())
                    .orElse(null);
            if (raced != null) {
                verifySameRequest(raced, requestHash);
                return DispatchCreationResult.replayed(details(raced));
            }
            throw new DispatchConflictException(
                    "Un autre dispatch est déjà en préparation pour cette date.");
        }
        if (claimResult.replay() != null) {
            return DispatchCreationResult.replayed(details(claimResult.replay()));
        }
        Claim claim = Objects.requireNonNull(claimResult.claim());

        try {
            ViaHubOptimizationResult optimization = optimizationFacade.optimize(claim.plan(), claim.runPolicy());
            transactionTemplate.executeWithoutResult(status -> complete(claim, optimization));
            return DispatchCreationResult.created(get(claim.dispatchPlanId()));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(
                    status -> fail(claim.dispatchPlanId(), claim.actorAccountId(), exception));
            throw new DispatchExecutionException(
                    claim.dispatchPlanId(),
                    "Le dispatch n'a pas pu être calculé. Les colis restent en attente.",
                    exception);
        }
    }

    public DispatchPlanDetails get(UUID dispatchPlanId) {
        return details(dispatchPlanRepository.findById(dispatchPlanId).orElseThrow(DispatchNotFoundException::new));
    }

    public List<DispatchPlanDetails> list(LocalDate serviceDate) {
        return dispatchPlanRepository.findAll(serviceDate).stream().map(this::details).toList();
    }

    private ClaimResult claimOrReplay(CreateDispatchCommand command, String requestHash) {
        dispatchSourceRepository.lockAdmissionGate();
        DispatchPlanRecord previous = dispatchPlanRepository
                .findByActorAndIdempotencyKey(command.actorAccountId(), command.idempotencyKey())
                .orElse(null);
        if (previous != null) {
            verifySameRequest(previous, requestHash);
            Instant staleBefore = Instant.now().minus(STALE_DISPATCH_TIMEOUT);
            if (previous.status() == DispatchStatus.GENERATING
                    && previous.createdAt().isBefore(staleBefore)
                    && dispatchPlanRepository.recoverStaleIdempotentPlan(
                            previous.serviceDate(),
                            previous.id(),
                            command.actorAccountId(),
                            staleBefore)) {
                previous = dispatchPlanRepository.findById(previous.id()).orElseThrow();
            }
            return ClaimResult.replay(previous);
        }
        dispatchPlanRepository.beginOperationDay(
                command.serviceDate(), command.actorAccountId(), Instant.now().minus(STALE_DISPATCH_TIMEOUT));
        OperationalSettings settings = settingsRepository.find()
                .orElseThrow(() -> new IllegalStateException("Les paramètres opérationnels sont absents."));
        validateOptimizationTimeLimit(settings.optimizationTimeLimitSeconds());
        Location hub = resolvedHub(command.hubOverride(), settings.defaultHub());
        List<SelectedCourierSnapshot> couriers = selectedCouriers(command.serviceDate(), command.courierIds());
        List<DispatchSourcePackage> packages = dispatchSourceRepository.findPending(
                command.serviceDate(), command.packageIds());
        if (packages.isEmpty()) {
            throw new DispatchInputException("Aucun colis en attente n'est disponible pour cette date.");
        }
        if (command.packageIds() != null && packages.size() != command.packageIds().size()) {
            Set<UUID> available = packages.stream()
                    .map(DispatchSourcePackage::packageId)
                    .collect(Collectors.toSet());
            List<UUID> unavailable = command.packageIds().stream()
                    .filter(id -> !available.contains(id))
                    .toList();
            throw new DispatchConflictException(
                    "La cohorte du plan ne peut plus être régénérée : " + unavailable.size()
                            + " colis ne sont plus en attente ou admissibles ("
                            + unavailable.stream().map(UUID::toString).collect(Collectors.joining(", ")) + ").");
        }
        validateMatrixSize(couriers, packages);

        UUID dispatchPlanId = UUID.randomUUID();
        DeliveryPlan plan = assemblePlan(dispatchPlanId, command.serviceDate(), hub, couriers, packages, settings);
        ViaHubRunPolicy runPolicy = runPolicy(settings);
        OperationalPolicySnapshot policySnapshot = snapshot(runPolicy.operationalPolicy());
        String inputJson = writeJson(new DispatchInputSnapshot(
                plan,
                packages,
                settings.version(),
                command.packageIds() == null));
        String policyJson = writeJson(policySnapshot);

        dispatchPlanRepository.createGenerating(
                dispatchPlanId,
                command.serviceDate(),
                command.actorAccountId(),
                command.idempotencyKey(),
                requestHash,
                hub,
                inputJson,
                policyJson,
                packages.size(),
                couriers.size());
        dispatchPlanRepository.attachOperationPlan(
                command.serviceDate(), dispatchPlanId, command.actorAccountId());
        dispatchPlanRepository.reserveDay(dispatchPlanId, command.serviceDate());
        dispatchPlanRepository.reservePackages(
                dispatchPlanId,
                packages.stream().map(DispatchSourcePackage::packageId).toList());
        dispatchPlanRepository.saveSelectedCouriers(dispatchPlanId, couriers);
        return ClaimResult.claim(
                new Claim(dispatchPlanId, command.actorAccountId(), plan, runPolicy, couriers, packages));
    }

    private void complete(Claim claim, ViaHubOptimizationResult optimization) {
        dispatchPlanRepository.lockOperationPlanForCompletion(claim.dispatchPlanId());
        Projection projection = project(claim, optimization);
        dispatchPlanRepository.updateCourierResults(claim.dispatchPlanId(), projection.couriers());
        dispatchPlanRepository.savePackageOutcomes(claim.dispatchPlanId(), projection.packages());
        dispatchPlanRepository.saveStops(claim.dispatchPlanId(), projection.stops());

        List<UUID> assignedIds = projection.packages().stream()
                .filter(DispatchPackageOutcome::assigned)
                .map(DispatchPackageOutcome::packageId)
                .toList();
        Map<UUID, String> unassignedReasons = projection.packages().stream()
                .filter(outcome -> !outcome.assigned())
                .collect(Collectors.toMap(
                        DispatchPackageOutcome::packageId,
                        outcome -> userReason(outcome.unassignedReason(), outcome.unassignedMessage()),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        dispatchSourceRepository.markAssigned(claim.dispatchPlanId(), assignedIds);
        dispatchSourceRepository.markUnassigned(claim.dispatchPlanId(), unassignedReasons);
        dispatchSourceRepository.recomputeRequestStatuses(
                claim.packages().stream().map(DispatchSourcePackage::deliveryRequestId).toList());

        DispatchStatus finalStatus = optimization.result().unassignedPackages().isEmpty()
                && optimization.result().warnings().isEmpty()
                ? DispatchStatus.READY
                : DispatchStatus.READY_WITH_WARNINGS;
        dispatchPlanRepository.markReady(
                claim.dispatchPlanId(),
                finalStatus,
                optimization,
                writeJson(optimization.result()),
                writeJson(optimization.hubExecution()),
                writeJson(optimization.metrics()));
        dispatchPlanRepository.markOperationDayDispatched(
                claim.dispatchPlanId(), claim.actorAccountId());
        dispatchPlanRepository.releaseReservations(claim.dispatchPlanId());
    }

    private void fail(UUID dispatchPlanId, UUID actorAccountId, RuntimeException exception) {
        dispatchPlanRepository.failOperationPlan(
                dispatchPlanId,
                actorAccountId,
                exception.getClass().getSimpleName(),
                exception.getMessage() == null ? "Échec technique pendant le calcul." : exception.getMessage());
        dispatchPlanRepository.releaseReservations(dispatchPlanId);
    }

    private Projection project(Claim claim, ViaHubOptimizationResult optimization) {
        Map<String, DispatchSourcePackage> sourceById = claim.packages().stream()
                .collect(Collectors.toMap(
                        source -> source.packageId().toString(),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        Map<String, PackageTransfer> transferByPackage = optimization.hubExecution().packageTransfers().stream()
                .collect(Collectors.toMap(
                        transfer -> transfer.deliveryPackage().packageId(),
                        Function.identity(),
                        (first, ignored) -> first));
        Map<String, UnassignedPackage> unassignedByPackage = optimization.result().unassignedPackages().stream()
                .collect(Collectors.toMap(UnassignedPackage::packageId, Function.identity()));

        Map<String, DeliveryPlacement> deliveries = new LinkedHashMap<>();
        for (CourierRoute route : optimization.result().routes()) {
            for (RouteStop stop : route.stops()) {
                if (stop.type() == RouteStopType.DELIVERY) {
                    deliveries.put(stop.packageId(), new DeliveryPlacement(
                            UUID.fromString(route.courierId()),
                            stop.order(),
                            stop.estimatedArrivalAt().toInstant()));
                }
            }
        }

        List<DispatchPackageOutcome> packageOutcomes = new ArrayList<>();
        for (DispatchSourcePackage source : claim.packages()) {
            String packageId = source.packageId().toString();
            PackageTransfer transfer = transferByPackage.get(packageId);
            DeliveryPlacement delivery = deliveries.get(packageId);
            UnassignedPackage unassigned = unassignedByPackage.get(packageId);
            packageOutcomes.add(new DispatchPackageOutcome(
                    source.packageId(),
                    source.deliveryRequestId(),
                    delivery != null,
                    transfer == null ? null : uuidOrNull(transfer.collectorCourierId()),
                    delivery == null ? null : delivery.courierId(),
                    transfer != null && transfer.transferred(),
                    delivery == null ? null : delivery.estimatedAt(),
                    delivery == null ? null : delivery.order(),
                    unassigned == null ? null : unassigned.reason().name(),
                    unassigned == null ? null : unassigned.message(),
                    source.recipientName(),
                    source.faritra(),
                    source.contact(),
                    source.amountToCollectAriary(),
                    source.mobileMoneyPaid(),
                    source.pricingSnapshot(),
                    source.effectiveWeightKg(),
                    source.destination()));
        }

        Map<String, CourierRoute> routeByCourier = optimization.result().routes().stream()
                .collect(Collectors.toMap(CourierRoute::courierId, Function.identity()));
        Map<String, CourierHubManifest> manifestByCourier = optimization.hubExecution().manifests().stream()
                .collect(Collectors.toMap(CourierHubManifest::courierId, Function.identity()));
        List<DispatchCourierResult> courierResults = new ArrayList<>();
        for (SelectedCourierSnapshot selected : claim.couriers()) {
            String courierId = selected.profileId().toString();
            CourierRoute route = routeByCourier.get(courierId);
            CourierHubManifest manifest = manifestByCourier.get(courierId);
            courierResults.add(new DispatchCourierResult(
                    selected.profileId(),
                    route != null || manifest != null,
                    manifest != null && manifest.roles().contains(HubRole.COLLECTION),
                    manifest != null && manifest.roles().contains(HubRole.DISTRIBUTION),
                    manifest == null || manifest.arrivedAt() == null ? null : manifest.arrivedAt().toInstant(),
                    manifest == null || manifest.departedAt() == null ? null : manifest.departedAt().toInstant(),
                    route == null || route.completedAt() == null ? null : route.completedAt().toInstant(),
                    manifest == null ? null : manifest.waitingSeconds(),
                    manifest == null ? null : manifest.loadAtArrivalKg(),
                    manifest == null ? null : manifest.loadAtDepartureKg(),
                    route == null ? null : Math.round(route.distanceMeters()),
                    route == null ? null : Math.round(route.durationSeconds())));
        }

        List<DispatchStop> stops = scheduleStops(claim, optimization, sourceById, manifestByCourier);
        return new Projection(courierResults, packageOutcomes, stops);
    }

    private List<DispatchStop> scheduleStops(
            Claim claim,
            ViaHubOptimizationResult optimization,
            Map<String, DispatchSourcePackage> sourceById,
            Map<String, CourierHubManifest> manifestByCourier) {
        Map<String, SelectedCourierSnapshot> selectedById = claim.couriers().stream()
                .collect(Collectors.toMap(item -> item.profileId().toString(), Function.identity()));
        List<DispatchStop> stops = new ArrayList<>();
        for (CourierRoute route : optimization.result().routes()) {
            UUID courierId = UUID.fromString(route.courierId());
            SelectedCourierSnapshot selected = selectedById.get(route.courierId());
            int order = 0;
            stops.add(new DispatchStop(
                    UUID.randomUUID(), courierId, order++, "START", null, null,
                    "Départ", selected.courier().departureLocation(),
                    selected.courier().departureAt().toInstant(), 0, 0, 0, 0));

            Set<String> pickupKeys = new LinkedHashSet<>();
            for (RouteStop raw : route.stops()) {
                if (raw.type() != RouteStopType.PICKUP) continue;
                String pickupKey = raw.deliveryGroupId() + "@" + raw.estimatedArrivalAt().toInstant();
                if (!pickupKeys.add(pickupKey)) continue;
                stops.add(dispatchStop(courierId, order++, raw, sourceById, "PICKUP"));
            }

            CourierHubManifest manifest = manifestByCourier.get(route.courierId());
            Instant hubArrival = manifest == null || manifest.arrivedAt() == null
                    ? null
                    : manifest.arrivedAt().toInstant();
            stops.add(new DispatchStop(
                    UUID.randomUUID(), courierId, order++, "HUB", null, null,
                    "Point de ralliement", claim.plan().redistributionHub(), hubArrival,
                    0, 0, 0, 0));

            for (RouteStop raw : route.stops()) {
                if (raw.type() != RouteStopType.DELIVERY) continue;
                stops.add(dispatchStop(courierId, order++, raw, sourceById, "DELIVERY"));
            }
        }
        return List.copyOf(stops);
    }

    private DispatchStop dispatchStop(
            UUID courierId,
            int order,
            RouteStop raw,
            Map<String, DispatchSourcePackage> sourceById,
            String type) {
        DispatchSourcePackage source = sourceById.get(raw.packageId());
        return new DispatchStop(
                UUID.randomUUID(),
                courierId,
                order,
                type,
                source == null ? null : source.deliveryRequestId(),
                raw.packageId() == null ? null : UUID.fromString(raw.packageId()),
                raw.type() == RouteStopType.PICKUP ? raw.deliveryGroupName() : raw.packageLabel(),
                raw.destination(),
                raw.estimatedArrivalAt() == null ? null : raw.estimatedArrivalAt().toInstant(),
                Math.max(0, Math.round(raw.distanceFromPreviousMeters())),
                Math.max(0, Math.round(raw.durationFromPreviousSeconds())),
                Math.max(0, Math.round(raw.cumulativeDistanceMeters())),
                Math.max(0, Math.round(raw.cumulativeDurationSeconds())));
    }

    private List<SelectedCourierSnapshot> selectedCouriers(LocalDate serviceDate, List<UUID> courierIds) {
        List<UUID> distinctIds = courierIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) throw new DispatchInputException("Sélectionnez au moins un livreur.");
        if (distinctIds.size() != courierIds.size()) {
            throw new DispatchInputException("Un livreur ne peut être sélectionné qu'une seule fois.");
        }
        Map<com.azylivraison.planning.domain.VehicleType, VehiclePresetConfiguration> presets =
                presetRepository.findAll().stream().collect(Collectors.toMap(
                        VehiclePresetConfiguration::vehicleType,
                        Function.identity()));
        List<SelectedCourierSnapshot> selected = new ArrayList<>();
        for (UUID id : distinctIds) {
            CourierProfile profile = courierRepository.findById(id)
                    .orElseThrow(() -> new DispatchInputException("Un livreur sélectionné n'existe plus."));
            if (!profile.active()) {
                throw new DispatchInputException("Le livreur " + profile.name() + " est inactif.");
            }
            VehiclePresetConfiguration preset = presets.get(profile.vehicleType());
            if (preset == null) throw new IllegalStateException("Le préréglage du véhicule est absent.");
            BigDecimal capacity = profile.maxWeightKg() == null
                    ? preset.maxWeightKg()
                    : profile.maxWeightKg();
            BigDecimal speed = profile.averageSpeedKmh() == null
                    ? preset.averageSpeedKmh()
                    : profile.averageSpeedKmh();
            ConfirmedLocation departure = profile.departureLocation();
            Courier courier = new Courier(
                    profile.id().toString(),
                    profile.name(),
                    profile.vehicleType(),
                    capacity,
                    speed,
                    serviceDate.atTime(profile.defaultDepartureTime()).atZone(ANTANANARIVO).toOffsetDateTime(),
                    location(departure));
            selected.add(new SelectedCourierSnapshot(profile.id(), profile.contact(), courier));
        }
        return List.copyOf(selected);
    }

    private DeliveryPlan assemblePlan(
            UUID dispatchPlanId,
            LocalDate serviceDate,
            Location hub,
            List<SelectedCourierSnapshot> couriers,
            List<DispatchSourcePackage> packages,
            OperationalSettings settings) {
        Map<UUID, List<DispatchSourcePackage>> byOwner = packages.stream()
                .collect(Collectors.groupingBy(
                        DispatchSourcePackage::ownerAccountId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<DeliveryClient> clients = new ArrayList<>();
        for (Map.Entry<UUID, List<DispatchSourcePackage>> owner : byOwner.entrySet()) {
            Map<UUID, List<DispatchSourcePackage>> byRequest = owner.getValue().stream()
                    .collect(Collectors.groupingBy(
                            DispatchSourcePackage::deliveryRequestId,
                            LinkedHashMap::new,
                            Collectors.toList()));
            List<DeliveryGroup> groups = new ArrayList<>();
            for (Map.Entry<UUID, List<DispatchSourcePackage>> request : byRequest.entrySet()) {
                DispatchSourcePackage first = request.getValue().getFirst();
                List<DeliveryPackage> deliveryPackages = request.getValue().stream()
                        .map(source -> new DeliveryPackage(
                                source.packageId().toString(),
                                source.recipientName(),
                                source.effectiveWeightKg(),
                                source.contact(),
                                source.destination(),
                                null))
                        .toList();
                groups.add(new DeliveryGroup(
                        request.getKey().toString(),
                        first.pickup().label(),
                        first.pickup(),
                        deliveryPackages));
            }
            clients.add(new DeliveryClient(
                    owner.getKey().toString(),
                    owner.getValue().getFirst().serviceClientName(),
                    groups));
        }
        List<Courier> planningCouriers = couriers.stream().map(SelectedCourierSnapshot::courier).toList();
        var earliestDeparture = planningCouriers.stream()
                .map(Courier::departureAt)
                .min(Comparator.comparing(value -> value.toInstant()))
                .orElseThrow();
        return new DeliveryPlan(
                "2.1",
                PlanningPattern.MULTI_CLIENT_MULTI_PICKUP_DELIVERY,
                dispatchPlanId.toString(),
                "Dispatch du " + serviceDate,
                earliestDeparture,
                "Indian/Antananarivo",
                new PlanSettings(false, settings.routingPreset()),
                null,
                hub,
                planningCouriers,
                List.of(),
                clients);
    }

    private ViaHubRunPolicy runPolicy(OperationalSettings settings) {
        return new ViaHubRunPolicy(
                new OperationalPolicy(
                        Duration.ofMinutes(settings.pickupServiceMinutes()),
                        Duration.ofMinutes(settings.hubServiceMinutes()),
                        Duration.ofMinutes(settings.deliveryServiceMinutes()),
                        Duration.ofMinutes(settings.earlyToleranceMinutes()),
                        Duration.ofMinutes(settings.lateToleranceMinutes())),
                Duration.ofSeconds(settings.optimizationTimeLimitSeconds()));
    }

    private void validateOptimizationTimeLimit(int timeLimitSeconds) {
        if (timeLimitSeconds <= 0 || timeLimitSeconds > MAX_OPTIMIZATION_TIME_LIMIT_SECONDS) {
            throw new DispatchInputException(
                    "Le temps maximal de calcul doit être compris entre 1 et "
                            + MAX_OPTIMIZATION_TIME_LIMIT_SECONDS + " secondes.");
        }
    }

    private OperationalPolicySnapshot snapshot(OperationalPolicy policy) {
        return new OperationalPolicySnapshot(
                policy.pickupServiceDuration().toSeconds(),
                policy.hubTransferDuration().toSeconds(),
                policy.deliveryServiceDuration().toSeconds(),
                policy.deliveryWindowEarlyTolerance().toSeconds(),
                policy.deliveryWindowLateTolerance().toSeconds(),
                "SERVICE_START");
    }

    private Location resolvedHub(Location override, ConfirmedLocation defaultHub) {
        if (override != null) {
            requireCoordinates(override, "Confirmez le point de ralliement sur la carte.");
            return namedHub(override);
        }
        if (defaultHub == null) {
            throw new DispatchInputException(
                    "Configurez un point de ralliement par défaut ou choisissez-en un pour ce dispatch.");
        }
        return namedHub(location(defaultHub));
    }

    private Location namedHub(Location hub) {
        String label = hub.label();
        if (label == null || label.isBlank()) {
            label = hub.destinationQuery();
        }
        if (label == null || label.isBlank()) {
            label = "Point de ralliement";
        }
        return new Location(label.trim(), hub.destinationQuery(), hub.latitude(), hub.longitude());
    }

    private Location location(ConfirmedLocation location) {
        return new Location(
                location.label(),
                location.destinationQuery(),
                location.latitude(),
                location.longitude());
    }

    private void validateMatrixSize(
            List<SelectedCourierSnapshot> couriers,
            List<DispatchSourcePackage> packages) {
        int uniqueWithdrawals = (int) packages.stream()
                .map(DispatchSourcePackage::deliveryRequestId)
                .distinct()
                .count();
        int collectionPoints = couriers.size() + uniqueWithdrawals + 1;
        int distributionPoints = packages.size() + 1;
        int limit = routingProperties.maxMatrixPoints();
        if (collectionPoints > limit || distributionPoints > limit) {
            int maximumPackages = Math.max(1, limit - 1);
            throw new DispatchInputException(
                    "Ce plan dépasse la capacité actuelle du service de routage ("
                            + limit + " points par calcul, soit au plus " + maximumPackages
                            + " destinations). Augmentez cette capacité avant de relancer le dispatch.");
        }
    }

    private void validateCommand(CreateDispatchCommand command) {
        if (command == null) throw new DispatchInputException("La demande de dispatch est obligatoire.");
        if (command.serviceDate() == null) throw new DispatchInputException("Choisissez la date du dispatch.");
        if (command.actorAccountId() == null) throw new DispatchInputException("L'administrateur est obligatoire.");
        String key = command.idempotencyKey();
        if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new DispatchInputException("La clé de sécurité du dispatch est absente ou invalide.");
        }
        if (command.courierIds().isEmpty()) throw new DispatchInputException("Sélectionnez au moins un livreur.");
        if (command.packageIds() != null) {
            if (command.packageIds().isEmpty()) {
                throw new DispatchInputException("La cohorte de colis à régénérer est vide.");
            }
            if (command.packageIds().stream().anyMatch(Objects::isNull)
                    || command.packageIds().stream().distinct().count() != command.packageIds().size()) {
                throw new DispatchInputException("La cohorte de colis à régénérer est invalide.");
            }
        }
        if (command.hubOverride() != null) {
            requireCoordinates(command.hubOverride(), "Confirmez le point de ralliement sur la carte.");
        }
    }

    private void requireCoordinates(Location location, String message) {
        if (location.latitude() == null || location.longitude() == null
                || !Double.isFinite(location.latitude()) || !Double.isFinite(location.longitude())) {
            throw new DispatchInputException(message);
        }
    }

    private String requestHash(CreateDispatchCommand command) {
        String couriers = command.courierIds().stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        Location hub = command.hubOverride();
        String packages = command.packageIds() == null
                ? "ALL_ELIGIBLE"
                : command.packageIds().stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        String canonical = command.serviceDate() + "\n" + couriers + "\n" + packages + "\n"
                + (hub == null ? "DEFAULT_HUB" : hub.latitude() + "," + hub.longitude() + ","
                        + Objects.toString(hub.label(), "") + "," + Objects.toString(hub.destinationQuery(), ""));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponible.", exception);
        }
    }

    private void verifySameRequest(DispatchPlanRecord previous, String requestHash) {
        if (!previous.requestHash().equals(requestHash)) {
            throw new DispatchConflictException(
                    "Cette clé de dispatch a déjà été utilisée avec une autre sélection.");
        }
    }

    private DispatchPlanDetails details(DispatchPlanRecord plan) {
        return new DispatchPlanDetails(
                plan,
                readJson(plan.policySnapshotJson(), OperationalPolicySnapshot.class),
                readJson(plan.resultJson(), OptimizationResult.class),
                readJson(plan.hubExecutionJson(), HubExecution.class),
                readJson(plan.metricsJson(), StrategyMetrics.class),
                plan.editable()
                        && dispatchPlanRepository.isActiveOperationPlan(plan.id())
                        && dispatchPlanRepository.countFinalOutcomes(plan.id()) == 0,
                dispatchPlanRepository.findPackageOutcomes(plan.id()),
                dispatchPlanRepository.findCouriers(plan.id()),
                dispatchPlanRepository.findStops(plan.id()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Le snapshot du dispatch n'a pas pu être enregistré.", exception);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Le planning enregistré est illisible.", exception);
        }
    }

    private UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private String userReason(String reason, String message) {
        if (reason == null && message == null) return "Aucune affectation réalisable.";
        if (reason == null) return message;
        return message == null ? reason : reason + " · " + message;
    }

    private record Claim(
            UUID dispatchPlanId,
            UUID actorAccountId,
            DeliveryPlan plan,
            ViaHubRunPolicy runPolicy,
            List<SelectedCourierSnapshot> couriers,
            List<DispatchSourcePackage> packages) {
    }

    private record ClaimResult(Claim claim, DispatchPlanRecord replay) {

        static ClaimResult claim(Claim claim) {
            return new ClaimResult(claim, null);
        }

        static ClaimResult replay(DispatchPlanRecord replay) {
            return new ClaimResult(null, replay);
        }
    }

    private record Projection(
            List<DispatchCourierResult> couriers,
            List<DispatchPackageOutcome> packages,
            List<DispatchStop> stops) {
    }

    private record DeliveryPlacement(UUID courierId, int order, Instant estimatedAt) {
    }

    private record DispatchInputSnapshot(
            DeliveryPlan plan,
            List<DispatchSourcePackage> businessPackages,
            long settingsVersion,
            boolean selectedEntirePendingQueue) {
    }
}
