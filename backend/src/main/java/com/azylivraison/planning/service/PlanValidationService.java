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
import com.azylivraison.planning.api.DeliveryPlanRequest.DeliveryTimeWindowRequest;
import com.azylivraison.planning.api.DeliveryPlanRequest.LocationRequest;
import com.azylivraison.planning.api.PlanningValidationError;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;
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
        validateUniquePackageIds(request.packages(), errors);

        Location depot = normalizeLocation(request.depot(), "depot", errors);
        List<Courier> couriers = normalizeCouriers(request.couriers(), errors);
        List<DeliveryPackage> packages = normalizePackages(request.packages(), errors);

        if (!errors.isEmpty()) {
            throw new PlanValidationException(errors);
        }

        return new DeliveryPlan(
                CURRENT_SCHEMA_VERSION,
                request.pattern(),
                request.planId().trim(),
                request.name().trim(),
                request.departureAt(),
                MVP_TIMEZONE,
                new PlanSettings(
                        request.settings().returnToDepot(),
                        request.settings().routingPreset()),
                depot,
                couriers,
                packages);
    }

    private void validateVersionAndPattern(
            DeliveryPlanRequest request,
            List<PlanningValidationError> errors) {
        if (!CURRENT_SCHEMA_VERSION.equals(request.schemaVersion().trim())) {
            errors.add(error(
                    "schemaVersion",
                    "UNSUPPORTED_SCHEMA_VERSION",
                    "La seule version de schéma prise en charge est 1.0."));
        }

        if (request.pattern() != PlanningPattern.SINGLE_PICKUP_MULTI_DELIVERY) {
            errors.add(error(
                    "pattern",
                    "UNSUPPORTED_PATTERN",
                    "Le MVP prend uniquement en charge un dépôt et plusieurs livraisons."));
        }
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
            List<PlanningValidationError> errors) {
        List<DeliveryPackage> packages = new ArrayList<>();

        for (int index = 0; index < requests.size(); index++) {
            DeliveryPackageRequest request = requests.get(index);
            DeliveryTimeWindow deliveryWindow = normalizeDeliveryWindow(
                    request.deliveryWindow(),
                    "packages[" + index + "].deliveryWindow",
                    errors);
            packages.add(new DeliveryPackage(
                    request.id().trim(),
                    request.label().trim(),
                    request.weightKg(),
                    normalizeOptional(request.phone()),
                    normalizeLocation(request.destination(), "packages[" + index + "].destination", errors),
                    deliveryWindow));
        }

        return List.copyOf(packages);
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
