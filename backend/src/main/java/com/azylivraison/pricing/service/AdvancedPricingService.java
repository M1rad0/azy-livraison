package com.azylivraison.pricing.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.azylivraison.administration.service.AdministrationConflictException;
import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.pricing.domain.AdvancedPricingPolicy;
import com.azylivraison.pricing.domain.AdvancedPricingQuote;
import com.azylivraison.pricing.domain.PricingCenter;
import com.azylivraison.pricing.domain.PricingTimeSlot;
import com.azylivraison.pricing.domain.PricingWeightTier;
import com.azylivraison.pricing.domain.PricingZone;
import com.azylivraison.pricing.repository.AdvancedPricingPolicyRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdvancedPricingService {

    private final AdvancedPricingPolicyRepository repository;

    public AdvancedPricingService(AdvancedPricingPolicyRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AdvancedPricingPolicy effectivePolicy(UUID clientId, DeliveryRequestKind kind) {
        return repository.findEffective(clientId, kind).orElseThrow(() ->
                new PricingConfigurationException("Aucune tarification active pour ce type de livraison."));
    }

    @Transactional(readOnly = true)
    public List<AdvancedPricingPolicy> effectivePolicies(UUID clientId) {
        return repository.findForClientWithDefaults(clientId);
    }

    @Transactional(readOnly = true)
    public List<AdvancedPricingPolicy> defaultPolicies() {
        return List.of(effectivePolicy(null, DeliveryRequestKind.NORMAL),
                effectivePolicy(null, DeliveryRequestKind.EXPRESS));
    }

    @Transactional(readOnly = true)
    public AdvancedPricingQuote quote(UUID clientId, DeliveryRequestKind kind, double latitude,
            double longitude, BigDecimal weightKg, LocalTime desiredTime) {
        return quote(effectivePolicy(clientId, kind), latitude, longitude, weightKg, desiredTime);
    }

    public AdvancedPricingQuote quote(AdvancedPricingPolicy policy, double latitude,
            double longitude, BigDecimal weightKg, LocalTime desiredTime) {
        double exactDistance = DeliveryPricingService.haversineMeters(
                policy.center().latitude(), policy.center().longitude(), latitude, longitude);
        long distance = Math.round(exactDistance);
        PricingZone zone = policy.zones().stream()
                .filter(item -> exactDistance <= item.maxDistanceMeters()).findFirst()
                .orElseThrow(() -> new DestinationOutsidePricingZonesException(distance,
                        policy.zones().getLast().maxDistanceMeters()));
        BigDecimal effectiveWeight = weightKg == null ? policy.defaultWeightKg() : weightKg;
        if (effectiveWeight.signum() <= 0) {
            throw new PricingConfigurationException("Le poids du colis doit etre positif.");
        }
        PricingWeightTier weightTier = policy.weightTiers().stream()
                .filter(item -> effectiveWeight.compareTo(item.maxWeightKg()) <= 0).findFirst().orElse(null);
        if (!policy.weightTiers().isEmpty() && weightTier == null) {
            throw new PricingConfigurationException(
                    "Aucun palier tarifaire ne couvre un colis de " + effectiveWeight.stripTrailingZeros().toPlainString()
                            + " kg.");
        }
        long weightSurcharge = weightTier == null ? 0 : weightTier.surchargeAriary();
        PricingTimeSlot slot = desiredTime == null ? null : policy.timeSlots().stream()
                .filter(item -> !desiredTime.isBefore(item.startTime()) && desiredTime.isBefore(item.endTime()))
                .findFirst().orElse(null);
        long timeSurcharge = slot == null ? 0 : slot.surchargeAriary();
        long total = Math.addExact(zone.deliveryFeeAriary(), Math.addExact(weightSurcharge, timeSurcharge));
        return new AdvancedPricingQuote(policy.id(), policy.version(), policy.clientAccountId(),
                policy.requestKind(), zone.id(), zone.name(), distance, zone.maxDistanceMeters(),
                zone.deliveryFeeAriary(), effectiveWeight, weightSurcharge, desiredTime,
                slot == null ? null : slot.label(), timeSurcharge, total);
    }

    @Transactional
    public AdvancedPricingPolicy save(UUID clientId, DeliveryRequestKind kind, PricingCenter center,
            BigDecimal defaultWeight, List<PricingZone> zones, List<PricingWeightTier> weights,
            List<PricingTimeSlot> slots, Long expectedVersion) {
        var existing = repository.findExact(clientId, kind);
        AdvancedPricingPolicy normalized = normalize(clientId, kind, center, defaultWeight, zones, weights, slots,
                existing.isPresent());
        if (existing.isEmpty()) {
            short id = repository.create(normalized);
            return repository.findExact(clientId, kind).orElseThrow();
        }
        long version = expectedVersion == null ? existing.get().version() : expectedVersion;
        AdvancedPricingPolicy update = new AdvancedPricingPolicy(existing.get().id(), clientId, kind,
                normalized.center(), normalized.defaultWeightKg(), normalized.zones(),
                normalized.weightTiers(), normalized.timeSlots(), existing.get().version(),
                existing.get().createdAt(), Instant.now());
        if (!repository.update(update, version)) {
            throw new AdministrationConflictException("PRICING_SETTINGS_VERSION_CONFLICT",
                    "Les tarifs ont ete modifies entre-temps. Rechargez la page.");
        }
        return repository.findExact(clientId, kind).orElseThrow();
    }

    @Transactional
    public void resetClientPolicy(UUID clientId, DeliveryRequestKind kind) {
        repository.deactivate(clientId, kind);
    }

    private AdvancedPricingPolicy normalize(UUID clientId, DeliveryRequestKind kind, PricingCenter center,
            BigDecimal defaultWeight, List<PricingZone> zones, List<PricingWeightTier> weights,
            List<PricingTimeSlot> slots, boolean preserveChildIds) {
        if (center == null || center.label() == null || center.label().isBlank())
            throw new PricingConfigurationException("Le point central est obligatoire.");
        if (defaultWeight == null || defaultWeight.signum() <= 0)
            throw new PricingConfigurationException("Le poids par defaut doit etre positif.");
        if (zones == null || zones.isEmpty())
            throw new PricingConfigurationException("Configurez au moins une zone tarifaire.");
        long previousRadius = 0;
        Set<String> names = new HashSet<>();
        var normalizedZones = new java.util.ArrayList<PricingZone>();
        for (PricingZone zone : zones) {
            if (zone.maxDistanceMeters() <= previousRadius || zone.deliveryFeeAriary() < 0)
                throw new PricingConfigurationException("Les rayons doivent etre croissants et les prix positifs.");
            if (!names.add(zone.name().trim().toLowerCase()))
                throw new PricingConfigurationException("Les noms des zones doivent etre uniques.");
            normalizedZones.add(new PricingZone(!preserveChildIds || zone.id() == null ? UUID.randomUUID() : zone.id(),
                    zone.name().trim(), zone.maxDistanceMeters(), zone.deliveryFeeAriary()));
            previousRadius = zone.maxDistanceMeters();
        }
        var normalizedWeights = new java.util.ArrayList<PricingWeightTier>();
        BigDecimal previousWeight = BigDecimal.ZERO;
        for (PricingWeightTier tier : weights == null ? List.<PricingWeightTier>of() : weights) {
            if (tier.maxWeightKg().compareTo(previousWeight) <= 0 || tier.surchargeAriary() < 0)
                throw new PricingConfigurationException("Les paliers de poids doivent etre croissants.");
            normalizedWeights.add(new PricingWeightTier(!preserveChildIds || tier.id() == null
                    ? UUID.randomUUID() : tier.id(),
                    tier.maxWeightKg(), tier.surchargeAriary()));
            previousWeight = tier.maxWeightKg();
        }
        var normalizedSlots = new java.util.ArrayList<PricingTimeSlot>();
        for (PricingTimeSlot slot : slots == null ? List.<PricingTimeSlot>of() : slots) {
            if (!slot.startTime().isBefore(slot.endTime()) || slot.surchargeAriary() < 0)
                throw new PricingConfigurationException("Chaque creneau doit avoir une plage et un montant valides.");
            boolean overlaps = normalizedSlots.stream().anyMatch(other ->
                    slot.startTime().isBefore(other.endTime()) && other.startTime().isBefore(slot.endTime()));
            if (overlaps) throw new PricingConfigurationException("Les creneaux horaires ne peuvent pas se chevaucher.");
            normalizedSlots.add(new PricingTimeSlot(!preserveChildIds || slot.id() == null
                    ? UUID.randomUUID() : slot.id(),
                    slot.label().trim(), slot.startTime(), slot.endTime(), slot.surchargeAriary()));
        }
        normalizedSlots.sort(Comparator.comparing(PricingTimeSlot::startTime));
        Instant now = Instant.now();
        return new AdvancedPricingPolicy((short) 0, clientId, kind,
                new PricingCenter(center.label().trim(), center.query(), center.latitude(), center.longitude()),
                defaultWeight, normalizedZones, normalizedWeights, normalizedSlots, 0, now, now);
    }
}
