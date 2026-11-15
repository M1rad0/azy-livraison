package com.azylivraison.pricing.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.azylivraison.administration.service.AdministrationConflictException;
import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.delivery.domain.DeliveryTimePreference;
import com.azylivraison.delivery.domain.DeliveryTimeWindow;
import com.azylivraison.pricing.domain.AdvancedPricingPolicy;
import com.azylivraison.pricing.domain.AdvancedPricingQuote;
import com.azylivraison.pricing.domain.PricingCenter;
import com.azylivraison.pricing.domain.PricingDistanceMode;
import com.azylivraison.pricing.domain.PricingKeywordRule;
import com.azylivraison.pricing.domain.PricingTimeSlot;
import com.azylivraison.pricing.domain.PricingWeightTier;
import com.azylivraison.pricing.domain.PricingZone;
import com.azylivraison.pricing.repository.AdvancedPricingPolicyRepository;
import com.azylivraison.routing.domain.RoutingPoint;
import com.azylivraison.routing.service.RoutingService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdvancedPricingService {

    private final AdvancedPricingPolicyRepository repository;
    private final RoutingService routingService;

    public AdvancedPricingService(AdvancedPricingPolicyRepository repository, RoutingService routingService) {
        this.repository = repository;
        this.routingService = routingService;
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
        return quote(clientId, kind, latitude, longitude, weightKg, desiredTime, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public AdvancedPricingQuote quote(UUID clientId, DeliveryRequestKind kind, double latitude,
            double longitude, BigDecimal weightKg, LocalTime desiredTime, LocalTime desiredEndTime,
            DeliveryTimePreference preference, String destinationLabel, String destinationQuery) {
        return quote(effectivePolicy(clientId, kind), latitude, longitude, weightKg, desiredTime,
                desiredEndTime, preference, destinationLabel, destinationQuery);
    }

    public AdvancedPricingQuote quote(AdvancedPricingPolicy policy, double latitude,
            double longitude, BigDecimal weightKg, LocalTime desiredTime) {
        return quote(policy, latitude, longitude, weightKg, desiredTime, null, null, null, null);
    }

    public AdvancedPricingQuote quote(AdvancedPricingPolicy policy, double latitude,
            double longitude, BigDecimal weightKg, LocalTime desiredTime, LocalTime desiredEndTime,
            DeliveryTimePreference preference, String destinationLabel, String destinationQuery) {
        DeliveryTimeWindow window = DeliveryTimeWindow.normalize(desiredTime, desiredEndTime, preference);
        double exactDistance = distanceMeters(policy, latitude, longitude);
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
        PricingTimeSlot slot = window.preference() != DeliveryTimePreference.CUSTOM ? null : policy.timeSlots().stream()
                .filter(item -> !window.start().isBefore(item.startTime()) && window.start().isBefore(item.endTime()))
                .findFirst().orElse(null);
        long timeSurcharge = switch (window.preference()) {
            case MORNING -> policy.morningSurchargeAriary();
            case AFTERNOON -> policy.afternoonSurchargeAriary();
            case CUSTOM -> slot == null ? 0 : slot.surchargeAriary();
            case NONE -> 0;
        };
        String timeLabel = switch (window.preference()) {
            case MORNING -> "Matin";
            case AFTERNOON -> "Soir";
            default -> slot == null ? null : slot.label();
        };
        PricingKeywordRule keyword = matchingKeyword(policy, destinationLabel, destinationQuery);
        long baseFee = keyword == null ? zone.deliveryFeeAriary() : keyword.deliveryFeeAriary();
        long total = Math.addExact(baseFee, Math.addExact(weightSurcharge, timeSurcharge));
        return new AdvancedPricingQuote(policy.id(), policy.version(), policy.clientAccountId(),
                policy.requestKind(), policy.distanceMode(), zone.id(), zone.name(),
                distance, zone.maxDistanceMeters(),
                baseFee, effectiveWeight, weightSurcharge, window.start(),
                timeLabel, timeSurcharge, total, window.end(), window.preference(),
                keyword == null ? null : keyword.keyword());
    }

    @Transactional
    public AdvancedPricingPolicy save(UUID clientId, DeliveryRequestKind kind, PricingDistanceMode distanceMode,
            PricingCenter center,
            BigDecimal defaultWeight, List<PricingZone> zones, List<PricingWeightTier> weights,
            List<PricingTimeSlot> slots, Long expectedVersion) {
        var existing = repository.findExact(clientId, kind);
        return save(clientId, kind, distanceMode, center, defaultWeight, zones, weights, slots,
                existing.map(AdvancedPricingPolicy::morningSurchargeAriary).orElse(500L),
                existing.map(AdvancedPricingPolicy::afternoonSurchargeAriary).orElse(500L),
                existing.map(AdvancedPricingPolicy::keywordRules).orElse(List.of()), expectedVersion);
    }

    @Transactional
    public AdvancedPricingPolicy save(UUID clientId, DeliveryRequestKind kind, PricingDistanceMode distanceMode,
            PricingCenter center, BigDecimal defaultWeight, List<PricingZone> zones,
            List<PricingWeightTier> weights, List<PricingTimeSlot> slots,
            long morningSurcharge, long afternoonSurcharge, List<PricingKeywordRule> keywordRules,
            Long expectedVersion) {
        var existing = repository.findExact(clientId, kind);
        AdvancedPricingPolicy normalized = normalize(clientId, kind, distanceMode, center, defaultWeight,
                zones, weights, slots, morningSurcharge, afternoonSurcharge, keywordRules, existing.isPresent());
        if (existing.isEmpty()) {
            short id = repository.create(normalized);
            return repository.findExact(clientId, kind).orElseThrow();
        }
        long version = expectedVersion == null ? existing.get().version() : expectedVersion;
        AdvancedPricingPolicy update = new AdvancedPricingPolicy(existing.get().id(), clientId, kind,
                normalized.distanceMode(), normalized.center(), normalized.defaultWeightKg(), normalized.zones(),
                normalized.weightTiers(), normalized.timeSlots(), normalized.morningSurchargeAriary(),
                normalized.afternoonSurchargeAriary(), normalized.keywordRules(), existing.get().version(),
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

    private AdvancedPricingPolicy normalize(UUID clientId, DeliveryRequestKind kind,
            PricingDistanceMode distanceMode, PricingCenter center,
            BigDecimal defaultWeight, List<PricingZone> zones, List<PricingWeightTier> weights,
            List<PricingTimeSlot> slots, long morningSurcharge, long afternoonSurcharge,
            List<PricingKeywordRule> keywordRules, boolean preserveChildIds) {
        if (distanceMode == null)
            throw new PricingConfigurationException("Le mode de calcul de distance est obligatoire.");
        if (center == null || center.label() == null || center.label().isBlank())
            throw new PricingConfigurationException("Le point central est obligatoire.");
        if (defaultWeight == null || defaultWeight.signum() <= 0)
            throw new PricingConfigurationException("Le poids par defaut doit etre positif.");
        if (zones == null || zones.isEmpty())
            throw new PricingConfigurationException("Configurez au moins une zone tarifaire.");
        if (morningSurcharge < 0 || afternoonSurcharge < 0)
            throw new PricingConfigurationException("Les supplements de periode doivent etre positifs ou nuls.");
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
        var normalizedKeywords = new java.util.ArrayList<PricingKeywordRule>();
        for (PricingKeywordRule rule : keywordRules == null ? List.<PricingKeywordRule>of() : keywordRules) {
            if (rule == null || rule.keyword() == null || normalizeText(rule.keyword()).isBlank()
                    || rule.keyword().length() > 200 || rule.deliveryFeeAriary() < 0)
                throw new PricingConfigurationException("Chaque regle doit avoir un mot-cle et un tarif valides.");
            normalizedKeywords.add(new PricingKeywordRule(!preserveChildIds || rule.id() == null
                    ? UUID.randomUUID() : rule.id(), rule.keyword().replaceAll("(?U)\\s+", " ").trim(),
                    rule.deliveryFeeAriary()));
        }
        if (normalizedKeywords.size() > 100)
            throw new PricingConfigurationException("Une grille accepte au maximum 100 regles de mots-cles.");
        Instant now = Instant.now();
        return new AdvancedPricingPolicy((short) 0, clientId, kind, distanceMode,
                new PricingCenter(center.label().trim(), center.query(), center.latitude(), center.longitude()),
                defaultWeight, normalizedZones, normalizedWeights, normalizedSlots,
                morningSurcharge, afternoonSurcharge, normalizedKeywords, 0, now, now);
    }

    private PricingKeywordRule matchingKeyword(AdvancedPricingPolicy policy, String label, String query) {
        String normalizedLabel = normalizeText(label);
        String normalizedQuery = normalizeText(query);
        PricingKeywordRule match = null;
        int matchLength = -1;
        for (PricingKeywordRule rule : policy.keywordRules()) {
            String keyword = normalizeText(rule.keyword());
            if (!keyword.isEmpty() && keyword.length() > matchLength
                    && (normalizedLabel.contains(keyword) || normalizedQuery.contains(keyword))) {
                match = rule;
                matchLength = keyword.length();
            }
        }
        return match;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").trim();
    }

    private double distanceMeters(AdvancedPricingPolicy policy, double latitude, double longitude) {
        if (policy.distanceMode() == PricingDistanceMode.AIR_DISTANCE) {
            return DeliveryPricingService.haversineMeters(
                    policy.center().latitude(), policy.center().longitude(), latitude, longitude);
        }
        return routingService.route(List.of(
                new RoutingPoint("pricing-center", policy.center().latitude(), policy.center().longitude()),
                new RoutingPoint("pricing-destination", latitude, longitude)))
                .distanceMeters();
    }
}
