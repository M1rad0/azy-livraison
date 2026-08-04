package com.azylivraison.planning.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.azylivraison.planning.domain.PlanningPattern;
import com.azylivraison.planning.domain.RoutingPreset;
import com.azylivraison.planning.domain.VehicleType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeliveryPlanRequest(
        @NotBlank(message = "La version du schéma est obligatoire.")
        String schemaVersion,

        @NotNull(message = "Le pattern de livraison est obligatoire.")
        PlanningPattern pattern,

        @NotBlank(message = "L'identifiant du plan est obligatoire.")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,99}",
                message = "L'identifiant du plan doit contenir uniquement lettres, chiffres, _ ou -.")
        String planId,

        @NotBlank(message = "Le nom du plan est obligatoire.")
        @Size(max = 200, message = "Le nom du plan ne peut pas dépasser 200 caractères.")
        String name,

        @NotNull(message = "La date et l'heure de départ sont obligatoires.")
        OffsetDateTime departureAt,

        @NotBlank(message = "Le fuseau horaire est obligatoire.")
        String timezone,

        @NotNull(message = "Les paramètres du plan sont obligatoires.")
        @Valid
        PlanSettingsRequest settings,

        @NotNull(message = "Le dépôt est obligatoire.")
        @Valid
        LocationRequest depot,

        @NotEmpty(message = "Au moins un livreur est obligatoire.")
        @Size(max = 200, message = "Un plan ne peut pas contenir plus de 200 livreurs.")
        List<@NotNull(message = "Un livreur ne peut pas être nul.") @Valid CourierRequest> couriers,

        @NotEmpty(message = "Au moins un colis est obligatoire.")
        @Size(max = 2_000, message = "Un plan ne peut pas contenir plus de 2 000 colis.")
        List<@NotNull(message = "Un colis ne peut pas être nul.") @Valid DeliveryPackageRequest> packages) {

    public record PlanSettingsRequest(
            @NotNull(message = "Le choix du retour au dépôt est obligatoire.")
            Boolean returnToDepot,

            @NotNull(message = "Le préréglage routier est obligatoire.")
            RoutingPreset routingPreset) {
    }

    public record LocationRequest(
            @Size(max = 200, message = "Le libellé du lieu ne peut pas dépasser 200 caractères.")
            String label,

            @Size(max = 500, message = "La destination textuelle ne peut pas dépasser 500 caractères.")
            String destinationQuery,

            @DecimalMin(value = "-90.0", message = "La latitude minimale est -90.")
            @DecimalMax(value = "90.0", message = "La latitude maximale est 90.")
            Double latitude,

            @DecimalMin(value = "-180.0", message = "La longitude minimale est -180.")
            @DecimalMax(value = "180.0", message = "La longitude maximale est 180.")
            Double longitude) {
    }

    public record CourierRequest(
            @NotBlank(message = "L'identifiant du livreur est obligatoire.")
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,99}",
                    message = "L'identifiant du livreur doit contenir uniquement lettres, chiffres, _ ou -.")
            String id,

            @NotBlank(message = "Le nom du livreur est obligatoire.")
            @Size(max = 200, message = "Le nom du livreur ne peut pas dépasser 200 caractères.")
            String name,

            @NotNull(message = "Le type de véhicule est obligatoire.")
            VehicleType vehicleType,

            @DecimalMin(value = "0.0", inclusive = false,
                    message = "La capacité maximale doit être strictement positive.")
            BigDecimal maxWeightKg,

            @DecimalMin(value = "0.0", inclusive = false,
                    message = "La vitesse moyenne doit être strictement positive.")
            BigDecimal averageSpeedKmh,

            OffsetDateTime departureAt,

            @Valid
            LocationRequest departureLocation) {
    }

    public record DeliveryTimeWindowRequest(
            @NotNull(message = "Le début du créneau est obligatoire lorsqu'un créneau est renseigné.")
            OffsetDateTime startAt,

            @NotNull(message = "La fin du créneau est obligatoire lorsqu'un créneau est renseigné.")
            OffsetDateTime endAt) {
    }

    public record DeliveryPackageRequest(
            @NotBlank(message = "L'identifiant du colis est obligatoire.")
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,99}",
                    message = "L'identifiant du colis doit contenir uniquement lettres, chiffres, _ ou -.")
            String id,

            @NotBlank(message = "Le libellé du colis est obligatoire.")
            @Size(max = 200, message = "Le libellé du colis ne peut pas dépasser 200 caractères.")
            String label,

            @NotNull(message = "Le poids du colis est obligatoire.")
            @DecimalMin(value = "0.0", inclusive = false,
                    message = "Le poids du colis doit être strictement positif.")
            BigDecimal weightKg,

            @Size(max = 50, message = "Le numéro de téléphone ne peut pas dépasser 50 caractères.")
            String phone,

            @NotNull(message = "La destination du colis est obligatoire.")
            @Valid
            LocationRequest destination,

            @Valid
            DeliveryTimeWindowRequest deliveryWindow) {
    }
}
