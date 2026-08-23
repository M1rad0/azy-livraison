package com.azylivraison.pricing.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.azylivraison.administration.service.AdministrationConflictException;
import com.azylivraison.pricing.domain.DeliveryPricingQuote;
import com.azylivraison.pricing.domain.PricingCenter;
import com.azylivraison.pricing.domain.PricingPolicy;
import com.azylivraison.pricing.domain.PricingZone;
import com.azylivraison.pricing.repository.PricingPolicyRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryPricingService {

    static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private final PricingPolicyRepository repository;

    public DeliveryPricingService(PricingPolicyRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PricingPolicy getPolicy() {
        return repository.find().orElseThrow(() -> new IllegalStateException(
                "La politique tarifaire n'a pas ete initialisee."));
    }

    @Transactional
    public PricingPolicy update(PricingCenter center, List<PricingZone> requestedZones, long expectedVersion) {
        PricingCenter normalizedCenter = normalize(center);
        List<PricingZone> zones = normalizeAndValidate(requestedZones);
        if (!repository.updateCenter(normalizedCenter, expectedVersion)) {
            throw new AdministrationConflictException(
                    "PRICING_SETTINGS_VERSION_CONFLICT",
                    "Les tarifs ont ete modifies entre-temps. Rechargez la page avant de reessayer.");
        }
        repository.replaceZones(zones);
        return getPolicy();
    }

    @Transactional(readOnly = true)
    public DeliveryPricingQuote quote(double latitude, double longitude) {
        return quote(getPolicy(), latitude, longitude);
    }

    public DeliveryPricingQuote quote(PricingPolicy policy, double latitude, double longitude) {
        requireCoordinates(latitude, longitude);
        double exactDistance = haversineMeters(
                policy.center().latitude(), policy.center().longitude(), latitude, longitude);
        long displayedDistance = Math.round(exactDistance);
        PricingZone zone = policy.zones().stream()
                .filter(candidate -> exactDistance <= candidate.maxDistanceMeters())
                .findFirst()
                .orElseThrow(() -> new DestinationOutsidePricingZonesException(
                        displayedDistance,
                        policy.zones().getLast().maxDistanceMeters()));
        return new DeliveryPricingQuote(
                zone.id(), zone.name(), zone.deliveryFeeAriary(), displayedDistance,
                zone.maxDistanceMeters(), policy.version());
    }

    static double haversineMeters(double fromLatitude, double fromLongitude,
            double toLatitude, double toLongitude) {
        double fromLatitudeRadians = Math.toRadians(fromLatitude);
        double toLatitudeRadians = Math.toRadians(toLatitude);
        double latitudeDelta = Math.toRadians(toLatitude - fromLatitude);
        double longitudeDelta = Math.toRadians(toLongitude - fromLongitude);
        double sinLatitude = Math.sin(latitudeDelta / 2);
        double sinLongitude = Math.sin(longitudeDelta / 2);
        double haversine = sinLatitude * sinLatitude
                + Math.cos(fromLatitudeRadians) * Math.cos(toLatitudeRadians)
                * sinLongitude * sinLongitude;
        double boundedHaversine = Math.min(1, Math.max(0, haversine));
        return 2 * EARTH_RADIUS_METERS * Math.atan2(
                Math.sqrt(boundedHaversine), Math.sqrt(1 - boundedHaversine));
    }

    private PricingCenter normalize(PricingCenter center) {
        if (center == null) {
            throw new PricingConfigurationException("Le point central est obligatoire.");
        }
        requireCoordinates(center.latitude(), center.longitude());
        String label = normalizeRequired(center.label(), "Le libelle du point central est obligatoire.");
        return new PricingCenter(label, normalizeNullable(center.query()), center.latitude(), center.longitude());
    }

    private List<PricingZone> normalizeAndValidate(List<PricingZone> requestedZones) {
        if (requestedZones == null || requestedZones.isEmpty()) {
            throw new PricingConfigurationException("Configurez au moins une zone tarifaire.");
        }
        Set<UUID> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<Long> radii = new HashSet<>();
        long previousRadius = 0;
        long previousFee = -1;
        java.util.ArrayList<PricingZone> normalized = new java.util.ArrayList<>(requestedZones.size());
        for (PricingZone requested : requestedZones) {
            UUID id = requested.id() == null ? UUID.randomUUID() : requested.id();
            String name = normalizeRequired(requested.name(), "Chaque zone doit avoir un nom.");
            if (!ids.add(id)) {
                throw new PricingConfigurationException("Les identifiants de zone doivent etre uniques.");
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new PricingConfigurationException("Les noms de zone doivent etre uniques.");
            }
            if (requested.maxDistanceMeters() <= previousRadius
                    || !radii.add(requested.maxDistanceMeters())) {
                throw new PricingConfigurationException(
                        "Les perimetres des zones doivent etre uniques et strictement croissants.");
            }
            if (requested.deliveryFeeAriary() < 0 || requested.deliveryFeeAriary() < previousFee) {
                throw new PricingConfigurationException(
                        "Les prix doivent etre positifs ou nuls et ne peuvent pas diminuer.");
            }
            normalized.add(new PricingZone(
                    id, name, requested.maxDistanceMeters(), requested.deliveryFeeAriary()));
            previousRadius = requested.maxDistanceMeters();
            previousFee = requested.deliveryFeeAriary();
        }
        return List.copyOf(normalized);
    }

    private void requireCoordinates(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new PricingConfigurationException("Les coordonnees geographiques sont invalides.");
        }
    }

    private String normalizeRequired(String value, String message) {
        String normalized = normalizeNullable(value);
        if (normalized == null) throw new PricingConfigurationException(message);
        return normalized;
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
