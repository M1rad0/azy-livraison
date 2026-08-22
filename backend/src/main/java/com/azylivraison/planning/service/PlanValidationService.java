package com.azylivraison.planning.service;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.azylivraison.planning.api.DeliveryPlanRequest;
import com.azylivraison.planning.api.DeliveryPlanRequest.CourierRequest;
import com.azylivraison.planning.api.DeliveryPlanRequest.DeliveryPackageRequest;
import com.azylivraison.planning.api.DeliveryPlanRequest.DeliveryClientRequest;
import com.azylivraison.planning.api.DeliveryPlanRequest.DeliveryGroupRequest;
import com.azylivraison.planning.api.DeliveryPlanRequest.DeliveryTimeWindowRequest;
import com.azylivraison.planning.api.DeliveryPlanRequest.LocationRequest;
import com.azylivraison.planning.api.PlanningValidationError;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.DeliveryClient;
import com.azylivraison.planning.domain.DeliveryGroup;
import com.azylivraison.planning.domain.DeliveryPlan;
import com.azylivraison.planning.domain.DeliveryTimeWindow;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.PlanSettings;
import com.azylivraison.planning.domain.PlanningPattern;
import com.azylivraison.planning.domain.VehiclePreset;

import org.springframework.stereotype.Service;

@Service
public class PlanValidationService {

    public static final String CURRENT_SCHEMA_VERSION = "1.0";
    /** Historical multi-withdrawal files stay in 2.0 and remain direct plans without a hub. */
    public static final String LEGACY_MULTI_SCHEMA_VERSION = "2.0";
    /** Version 2.1 introduces a confirmed redistribution hub and confirmed courier departures. */
    public static final String CURRENT_MULTI_SCHEMA_VERSION = "2.1";
    public static final String MVP_TIMEZONE = "Indian/Antananarivo";

    private final VehiclePresetService vehiclePresetService;

    public PlanValidationService(VehiclePresetService vehiclePresetService) {
        this.vehiclePresetService = vehiclePresetService;
    }

    public DeliveryPlan validateAndNormalize(DeliveryPlanRequest request) {
        List<PlanningValidationError> errors = new ArrayList<>();

        validateVersionAndPattern(request, errors);
        validateTimezoneAndOffset(request, errors);
        validateUniqueIds(request.couriers(), "couriers", errors);
        List<DeliveryPackageRequest> packageRequests = request.packages() == null ? List.of() : request.packages();
        List<DeliveryClientRequest> clientRequests = request.clients() == null ? List.of() : request.clients();
        validatePatternShape(request, packageRequests, clientRequests, errors);
        validateUniquePackageIds(packageRequests, clientRequests, errors);

        Location depot = request.depot() == null ? null : normalizeLocation(request.depot(), "depot", errors);
        Location redistributionHub = request.redistributionHub() == null
                ? null
                : normalizeLocation(request.redistributionHub(), "redistributionHub", errors);
        List<Courier> couriers = normalizeCouriers(request.couriers(), errors);
        List<DeliveryPackage> packages = normalizePackages(packageRequests, "packages", errors);
        List<DeliveryClient> clients = normalizeClients(clientRequests, errors);

        if (!errors.isEmpty()) {
            throw new PlanValidationException(errors);
        }

        return new DeliveryPlan(
                request.schemaVersion().trim(),
                request.pattern(),
                request.planId().trim(),
                request.name().trim(),
                request.departureAt(),
                MVP_TIMEZONE,
                new PlanSettings(
                        request.settings().returnToDepot(),
                        request.settings().routingPreset()),
                depot,
                redistributionHub,
                couriers,
                packages,
                clients);
    }

    private void validateVersionAndPattern(
            DeliveryPlanRequest request,
            List<PlanningValidationError> errors) {
        String schemaVersion = request.schemaVersion().trim();
        if (request.pattern() == PlanningPattern.MULTI_CLIENT_MULTI_PICKUP_DELIVERY) {
            if (LEGACY_MULTI_SCHEMA_VERSION.equals(schemaVersion)
                    || CURRENT_MULTI_SCHEMA_VERSION.equals(schemaVersion)) {
                return;
            }
            errors.add(error(
                    "schemaVersion",
                    "UNSUPPORTED_SCHEMA_VERSION",
                    "Ce mode utilise la version de fichier " + CURRENT_MULTI_SCHEMA_VERSION
                            + ". Les fichiers historiques " + LEGACY_MULTI_SCHEMA_VERSION + " restent acceptés."));
            return;
        }

        if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            errors.add(error(
                    "schemaVersion",
                    "UNSUPPORTED_SCHEMA_VERSION",
                    "Ce mode utilise la version de fichier " + CURRENT_SCHEMA_VERSION + "."));
        }
    }

    private void validatePatternShape(
            DeliveryPlanRequest request,
            List<DeliveryPackageRequest> packages,
            List<DeliveryClientRequest> clients,
            List<PlanningValidationError> errors) {
        if (request.pattern() == PlanningPattern.SINGLE_PICKUP_MULTI_DELIVERY) {
            if (request.depot() == null) {
                errors.add(error("depot", "PICKUP_REQUIRED", "Le point de récupération est obligatoire."));
            }
            if (packages.isEmpty()) {
                errors.add(error("packages", "PACKAGE_REQUIRED", "Ajoutez au moins un colis."));
            }
            if (!clients.isEmpty()) {
                errors.add(error("clients", "FIELD_NOT_ALLOWED", "Les clients appartiennent au mode multi-clients."));
            }
            if (request.redistributionHub() != null) {
                errors.add(error(
                        "redistributionHub",
                        "FIELD_NOT_ALLOWED",
                        "Le siège de redistribution appartient au mode multi-retraits 2.1."));
            }
            return;
        }

        if (request.depot() != null || !packages.isEmpty()) {
            errors.add(error(
                    "pattern",
                    "LEGACY_FIELDS_NOT_ALLOWED",
                    "Le mode multi-clients utilise les points de récupération et colis de chaque groupe."));
        }
        if (clients.isEmpty()) {
            errors.add(error("clients", "CLIENT_REQUIRED", "Ajoutez au moins un client."));
        }
        if (request.settings().returnToDepot()) {
            errors.add(error(
                    "settings.returnToDepot",
                    "RETURN_NOT_SUPPORTED",
                    "Le retour commun n'est pas disponible lorsqu'il existe plusieurs points de récupération."));
        }

        String schemaVersion = request.schemaVersion().trim();
        if (CURRENT_MULTI_SCHEMA_VERSION.equals(schemaVersion)) {
            validateHubRequirements(request, errors);
        } else if (LEGACY_MULTI_SCHEMA_VERSION.equals(schemaVersion)
                && request.redistributionHub() != null) {
            errors.add(error(
                    "redistributionHub",
                    "FIELD_NOT_ALLOWED",
                    "Un fichier historique 2.0 ne contient pas de siège. Utilisez la version 2.1."));
        }
    }

    private void validateHubRequirements(
            DeliveryPlanRequest request,
            List<PlanningValidationError> errors) {
        if (request.redistributionHub() == null) {
            errors.add(error(
                    "redistributionHub",
                    "REDISTRIBUTION_HUB_REQUIRED",
                    "Ajoutez et confirmez le siège de redistribution."));
        } else if (!hasConfirmedCoordinates(request.redistributionHub())) {
            errors.add(error(
                    "redistributionHub",
                    "CONFIRMED_LOCATION_REQUIRED",
                    "Confirmez le siège de redistribution sur la carte."));
        }

        for (int index = 0; index < request.couriers().size(); index++) {
            CourierRequest courier = request.couriers().get(index);
            String path = "couriers[" + index + "]";
            if (courier.departureAt() == null) {
                errors.add(error(
                        path + ".departureAt",
                        "COURIER_DEPARTURE_TIME_REQUIRED",
                        "Renseignez l'heure de départ de ce livreur pour le passage au siège."));
            }
            if (courier.departureLocation() == null) {
                errors.add(error(
                        path + ".departureLocation",
                        "COURIER_DEPARTURE_LOCATION_REQUIRED",
                        "Ajoutez et confirmez le point de départ de ce livreur."));
            } else if (!hasConfirmedCoordinates(courier.departureLocation())) {
                errors.add(error(
                        path + ".departureLocation",
                        "CONFIRMED_LOCATION_REQUIRED",
                        "Confirmez le point de départ de ce livreur sur la carte."));
            }
        }
    }

    private boolean hasConfirmedCoordinates(LocationRequest location) {
        return location.latitude() != null && location.longitude() != null;
    }

    private void validateTimezoneAndOffset(
            DeliveryPlanRequest request,
            List<PlanningValidationError> errors) {
        String timezone = request.timezone().trim();
        if (!MVP_TIMEZONE.equals(timezone)) {
            errors.add(error(
                    "timezone",
                    "UNSUPPORTED_TIMEZONE",
                    "Le fuseau du MVP doit être Indian/Antananarivo."));
            return;
        }

        try {
            ZoneId zoneId = ZoneId.of(timezone);
            if (!zoneId.getRules().getOffset(request.departureAt().toInstant())
                    .equals(request.departureAt().getOffset())) {
                errors.add(error(
                        "departureAt",
                        "TIMEZONE_OFFSET_MISMATCH",
                        "L'heure de départ doit utiliser le décalage du fuseau Indian/Antananarivo."));
            }
        } catch (DateTimeException exception) {
            errors.add(error(
                    "timezone",
                    "INVALID_TIMEZONE",
                    "Le fuseau horaire n'est pas valide."));
        }
    }

    private void validateUniqueIds(
            List<CourierRequest> couriers,
            String collectionPath,
            List<PlanningValidationError> errors) {
        Map<String, Integer> firstIndexes = new HashMap<>();

        for (int index = 0; index < couriers.size(); index++) {
            String id = couriers.get(index).id().trim();
            Integer firstIndex = firstIndexes.putIfAbsent(id, index);
            if (firstIndex != null) {
                errors.add(error(
                        collectionPath + "[" + index + "].id",
                        "DUPLICATE_ID",
                        "Cet identifiant de livreur est déjà utilisé à l'index " + firstIndex + "."));
            }
        }
    }

    private void validateUniquePackageIds(
            List<DeliveryPackageRequest> packages,
            List<DeliveryClientRequest> clients,
            List<PlanningValidationError> errors) {
        Map<String, Integer> firstIndexes = new HashMap<>();

        for (int index = 0; index < packages.size(); index++) {
            String id = packages.get(index).id().trim();
            Integer firstIndex = firstIndexes.putIfAbsent(id, index);
            if (firstIndex != null) {
                errors.add(error(
                        "packages[" + index + "].id",
                        "DUPLICATE_ID",
                        "Cet identifiant de colis est déjà utilisé à l'index " + firstIndex + "."));
            }
        }

        Map<String, String> firstPaths = new HashMap<>();
        for (int clientIndex = 0; clientIndex < clients.size(); clientIndex++) {
            DeliveryClientRequest client = clients.get(clientIndex);
            for (int groupIndex = 0; groupIndex < client.deliveryGroups().size(); groupIndex++) {
                DeliveryGroupRequest group = client.deliveryGroups().get(groupIndex);
                for (int packageIndex = 0; packageIndex < group.packages().size(); packageIndex++) {
                    String id = group.packages().get(packageIndex).id().trim();
                    String path = "clients[" + clientIndex + "].deliveryGroups[" + groupIndex
                            + "].packages[" + packageIndex + "].id";
                    String firstPath = firstPaths.putIfAbsent(id, path);
                    if (firstPath != null) {
                        errors.add(error(path, "DUPLICATE_ID", "Cet identifiant de colis est déjà utilisé dans "
                                + firstPath + "."));
                    }
                }
            }
        }
    }

    private List<Courier> normalizeCouriers(
            List<CourierRequest> requests,
            List<PlanningValidationError> errors) {
        List<Courier> couriers = new ArrayList<>();

        for (int index = 0; index < requests.size(); index++) {
            CourierRequest request = requests.get(index);
                    VehiclePreset preset = vehiclePresetService.get(request.vehicleType());
                    BigDecimal maxWeightKg = request.maxWeightKg() != null
                            ? request.maxWeightKg()
                            : preset.maxWeightKg();
                    BigDecimal averageSpeedKmh = request.averageSpeedKmh() != null
                            ? request.averageSpeedKmh()
                            : preset.averageSpeedKmh();

            validateOptionalOffset(request.departureAt(), "couriers[" + index + "].departureAt", errors);
            Location departureLocation = request.departureLocation() == null
                    ? null
                    : normalizeLocation(request.departureLocation(), "couriers[" + index + "].departureLocation", errors);

            couriers.add(new Courier(
                            request.id().trim(),
                            request.name().trim(),
                            request.vehicleType(),
                            maxWeightKg,
                            averageSpeedKmh,
                            request.departureAt(),
                            departureLocation));
        }

        return List.copyOf(couriers);
    }

    private List<DeliveryPackage> normalizePackages(
            List<DeliveryPackageRequest> requests,
            String collectionPath,
            List<PlanningValidationError> errors) {
        List<DeliveryPackage> packages = new ArrayList<>();

        for (int index = 0; index < requests.size(); index++) {
            DeliveryPackageRequest request = requests.get(index);
            DeliveryTimeWindow deliveryWindow = normalizeDeliveryWindow(
                    request.deliveryWindow(),
                    collectionPath + "[" + index + "].deliveryWindow",
                    errors);
            packages.add(new DeliveryPackage(
                    request.id().trim(),
                    request.label().trim(),
                    request.weightKg(),
                    normalizeOptional(request.phone()),
                    normalizeLocation(request.destination(), collectionPath + "[" + index + "].destination", errors),
                    deliveryWindow));
        }

        return List.copyOf(packages);
    }

    private List<DeliveryClient> normalizeClients(
            List<DeliveryClientRequest> requests,
            List<PlanningValidationError> errors) {
        List<DeliveryClient> clients = new ArrayList<>();
        Map<String, Integer> clientIds = new HashMap<>();
        Map<String, String> groupIds = new HashMap<>();

        for (int clientIndex = 0; clientIndex < requests.size(); clientIndex++) {
            DeliveryClientRequest request = requests.get(clientIndex);
            String clientId = request.id().trim();
            Integer previousClient = clientIds.putIfAbsent(clientId, clientIndex);
            if (previousClient != null) {
                errors.add(error("clients[" + clientIndex + "].id", "DUPLICATE_ID",
                        "Cet identifiant de client est déjà utilisé à l'index " + previousClient + "."));
            }

            List<DeliveryGroup> groups = new ArrayList<>();
            for (int groupIndex = 0; groupIndex < request.deliveryGroups().size(); groupIndex++) {
                DeliveryGroupRequest group = request.deliveryGroups().get(groupIndex);
                String groupPath = "clients[" + clientIndex + "].deliveryGroups[" + groupIndex + "]";
                String groupId = group.id().trim();
                String previousPath = groupIds.putIfAbsent(groupId, groupPath + ".id");
                if (previousPath != null) {
                    errors.add(error(groupPath + ".id", "DUPLICATE_ID",
                            "Cet identifiant de groupe est déjà utilisé dans " + previousPath + "."));
                }
                groups.add(new DeliveryGroup(
                        groupId,
                        group.name().trim(),
                        normalizeLocation(group.pickup(), groupPath + ".pickup", errors),
                        normalizePackages(group.packages(), groupPath + ".packages", errors)));
            }
            clients.add(new DeliveryClient(clientId, request.name().trim(), groups));
        }
        return List.copyOf(clients);
    }

    private DeliveryTimeWindow normalizeDeliveryWindow(
            DeliveryTimeWindowRequest request,
            String path,
            List<PlanningValidationError> errors) {
        if (request == null) {
            return null;
        }

        validateOptionalOffset(request.startAt(), path + ".startAt", errors);
        validateOptionalOffset(request.endAt(), path + ".endAt", errors);
        if (request.startAt() != null && request.endAt() != null
                && !request.endAt().isAfter(request.startAt())) {
            errors.add(error(
                    path,
                    "INVALID_TIME_WINDOW",
                    "La fin du créneau doit être postérieure à son début."));
        }
        return new DeliveryTimeWindow(request.startAt(), request.endAt());
    }

    private void validateOptionalOffset(
            java.time.OffsetDateTime value,
            String path,
            List<PlanningValidationError> errors) {
        if (value == null) {
            return;
        }
        ZoneId zoneId = ZoneId.of(MVP_TIMEZONE);
        if (!zoneId.getRules().getOffset(value.toInstant()).equals(value.getOffset())) {
            errors.add(error(
                    path,
                    "TIMEZONE_OFFSET_MISMATCH",
                    "L'heure doit utiliser le décalage du fuseau Indian/Antananarivo."));
        }
    }

    private Location normalizeLocation(
            LocationRequest request,
            String path,
            List<PlanningValidationError> errors) {
        boolean hasLatitude = request.latitude() != null;
        boolean hasLongitude = request.longitude() != null;
        String destinationQuery = normalizeOptional(request.destinationQuery());

        if (hasLatitude != hasLongitude) {
            errors.add(error(
                    path,
                    "INCOMPLETE_COORDINATES",
                    "La latitude et la longitude doivent être fournies ensemble."));
        } else if (!hasLatitude && destinationQuery == null) {
            errors.add(error(
                    path,
                    "DESTINATION_REQUIRED",
                    "Fournissez des coordonnées complètes ou une destination textuelle."));
        }

        return new Location(
                normalizeOptional(request.label()),
                destinationQuery,
                request.latitude(),
                request.longitude());
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private PlanningValidationError error(String path, String code, String message) {
        return new PlanningValidationError(path, code, message);
    }
}
