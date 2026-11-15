package com.azylivraison.dispatch.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.azylivraison.administration.repository.OperationalSettingsRepository;
import com.azylivraison.dispatch.domain.*;
import com.azylivraison.dispatch.domain.ManualDispatchDraft.*;
import com.azylivraison.dispatch.domain.ManualDispatchDraft.Collection;
import com.azylivraison.dispatch.repository.DispatchPlanRepository;
import com.azylivraison.dispatch.repository.DispatchSourceRepository;
import com.azylivraison.dispatch.repository.ManualDispatchDraftRepository;
import com.azylivraison.optimization.domain.OptimizationComparisonResult.OperationalPolicySnapshot;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.Location;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Human assignments are authoritative; no solver, capacity or window constraints are imposed. */
@Service
public class ManualDispatchService {
    private static final ZoneId ANTANANARIVO = ZoneId.of("Indian/Antananarivo");
    private final ManualDispatchDraftRepository drafts;
    private final DispatchPlanRepository plans;
    private final DispatchSourceRepository source;
    private final CreateDispatchService reader;
    private final DispatchPlanControlService controls;
    private final OperationalSettingsRepository settings;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ManualDispatchService(ManualDispatchDraftRepository drafts, DispatchPlanRepository plans,
            DispatchSourceRepository source, CreateDispatchService reader, DispatchPlanControlService controls,
            OperationalSettingsRepository settings, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.drafts = drafts;
        this.plans = plans;
        this.source = source;
        this.reader = reader;
        this.controls = controls;
        this.settings = settings;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<ManualDispatchDraft> active(LocalDate date) { return drafts.active(date); }
    public Optional<ManualDispatchDraft> forPlan(UUID id) { return drafts.forPlan(id); }
    public ManualDispatchDraft get(UUID id) { return drafts.find(id, false).orElseThrow(DispatchNotFoundException::new); }

    /** Refresh commercial inputs after an authorized pre-validation edit; stale editors
     * must reload instead of overwriting the refreshed snapshot with an old version. */
    @Transactional
    public void synchronizePendingPackages(LocalDate date, UUID actor) {
        source.lockAdmissionGate();
        var active = drafts.active(date);
        if (active.isEmpty()) return;
        ManualDispatchDraft current = drafts.find(active.get().id(), true).orElseThrow(DispatchNotFoundException::new);
        if (!"DRAFT".equals(current.status())) return;
        List<DispatchSourcePackage> packages = source.findPending(date);
        if (packages.isEmpty()) { cancel(current.id(), current.version(), actor); return; }
        List<Pickup> pickups = groupedPickups(packages);
        Set<String> pickupIds = pickups.stream().map(Pickup::id).collect(Collectors.toSet());
        Set<UUID> packageIds = packages.stream().map(DispatchSourcePackage::packageId).collect(Collectors.toSet());
        List<Collection> collections = new ArrayList<>();
        List<Delivery> deliveries = new ArrayList<>();
        for (DraftCourier courier : current.couriers()) {
            var retainedPickups = current.collections().stream().filter(c -> c.courierId().equals(courier.courierId()) && pickupIds.contains(c.pickupId()))
                    .sorted(Comparator.comparingInt(Collection::order)).toList();
            for (int i = 0; i < retainedPickups.size(); i++) collections.add(new Collection(retainedPickups.get(i).pickupId(), courier.courierId(), i));
            var retainedPackages = current.deliveries().stream().filter(d -> d.courierId().equals(courier.courierId()) && packageIds.contains(d.packageId()))
                    .sorted(Comparator.comparingInt(Delivery::order)).toList();
            for (int i = 0; i < retainedPackages.size(); i++) deliveries.add(new Delivery(retainedPackages.get(i).packageId(), courier.courierId(), i));
        }
        boolean completePickups = collections.size() == pickups.size();
        boolean completePackages = deliveries.size() == packages.size();
        Step step = !completePickups ? Step.COLLECTION : current.step() == Step.REVIEW && !completePackages ? Step.DELIVERY : current.step();
        ManualDispatchDraft updated = new ManualDispatchDraft(current.id(), date, current.version() + 1, "DRAFT", step,
                current.hub(), current.couriers(), packages, pickups, collections, deliveries, null);
        drafts.update(updated, current.version());
        drafts.event(date, actor, "MANUAL_DISPATCH_INPUTS_UPDATED", Map.of("draftId", current.id(), "version", updated.version()));
    }

    @Transactional
    public ManualDispatchDraft create(CreateDispatchCommand command) {
        if (command.serviceDate() == null || command.actorAccountId() == null
                || command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                || command.idempotencyKey().length() > 100) {
            throw new DispatchInputException("La date et la clé de sécurité du dispatch sont obligatoires.");
        }
        source.lockAdmissionGate();
        String hash = hash(command);
        var replay = drafts.replay(command.actorAccountId(), command.idempotencyKey(), hash);
        if (replay.isPresent()) return replay.get();
        var config = settings.find().orElseThrow();
        Location hub = reader.resolvedHub(command.hubOverride(), config.defaultHub());
        List<DraftCourier> couriers = reader.selectedCouriers(command.serviceDate(), command.courierIds())
                .stream().map(selected -> {
                    Courier c = selected.courier();
                    return new DraftCourier(selected.profileId(), c.name(), selected.contact(), c.vehicleType(),
                            c.maxWeightKg(), c.averageSpeedKmh(), c.departureAt(), c.departureLocation());
                }).toList();
        return start(command.serviceDate(), hub, couriers, command.actorAccountId(), command.idempotencyKey(), hash, null);
    }

    private ManualDispatchDraft start(LocalDate date, Location hub, List<DraftCourier> couriers,
            UUID actor, String key, String hash, ManualDispatchDraft previous) {
        plans.beginOperationDay(date, actor, Instant.now().minusSeconds(1800));
        List<DispatchSourcePackage> packages = source.findPending(date);
        if (packages.isEmpty()) throw new DispatchInputException("Aucun colis normal n'est en attente pour cette date.");
        List<Pickup> pickups = groupedPickups(packages);
        ManualDispatchDraft draft = new ManualDispatchDraft(UUID.randomUUID(), date, 0, "DRAFT", Step.COLLECTION,
                hub, couriers, packages, pickups, List.of(), List.of(), null);
        if (previous != null) {
            if (!packageIds(previous).equals(packageIds(draft))) {
                throw new DispatchConflictException("La liste des colis a changé. Recommencez la préparation du dispatch.");
            }
            // Retain stable group IDs and the exact commercial snapshot of the validated plan.
            draft = new ManualDispatchDraft(draft.id(), date, 0, "DRAFT", Step.REVIEW,
                    hub, couriers, previous.packages(), previous.pickups(), previous.collections(), previous.deliveries(), null);
        }
        drafts.insert(draft, actor, key, hash);
        // Admissions for normal requests are closed, but independent express activity remains available.
        jdbc.update("UPDATE delivery_operation_day SET status = 'OPEN' WHERE service_date = ? AND status = 'DISPATCHING'", date);
        drafts.event(date, actor, "MANUAL_DISPATCH_DRAFT_CREATED", Map.of("draftId", draft.id()));
        return draft;
    }

    @Transactional
    public ManualDispatchDraft save(UUID id, long version, Step step, List<DraftCourier> couriers,
            List<Collection> collections, List<Delivery> deliveries, UUID actor) {
        source.lockAdmissionGate();
        ManualDispatchDraft current = editable(id, version);
        List<DraftCourier> departures = validateDepartures(current, couriers);
        ManualDispatchDraft next = current.withLayout(version + 1, "DRAFT", step, departures, collections, deliveries, null);
        validateLayout(next, false);
        drafts.update(next, version);
        drafts.event(next.serviceDate(), actor, "MANUAL_DISPATCH_DRAFT_SAVED", Map.of("draftId", id, "version", next.version()));
        return next;
    }

    @Transactional
    public DispatchPlanDetails finalizeDraft(UUID id, long version, UUID actor) {
        source.lockAdmissionGate();
        ManualDispatchDraft draft = drafts.find(id, true).orElseThrow(DispatchNotFoundException::new);
        if ("FINALIZED".equals(draft.status())) return reader.get(draft.finalPlanId());
        requireEditable(draft, version);
        validateLayout(draft, true);
        jdbc.queryForList("""
                SELECT p.id FROM delivery_package p JOIN delivery_request dr ON dr.id = p.delivery_request_id
                WHERE dr.requested_date = ? AND dr.request_kind = 'NORMAL' ORDER BY p.id FOR UPDATE
                """, UUID.class, draft.serviceDate());
        // Re-read under the admission lock: never dispatch a cancelled or concurrently changed package.
        List<DispatchSourcePackage> pending = source.findPending(draft.serviceDate());
        Map<UUID, DispatchSourcePackage> currentInputs = pending.stream().collect(Collectors.toMap(DispatchSourcePackage::packageId, Function.identity()));
        Map<UUID, DispatchSourcePackage> savedInputs = draft.packages().stream().collect(Collectors.toMap(DispatchSourcePackage::packageId, Function.identity()));
        if (!currentInputs.equals(savedInputs)) {
            throw new DispatchConflictException("Les colis de la journée ont changé. Abandonnez ce brouillon puis recréez-le.");
        }
        int claimed = jdbc.update("""
                UPDATE delivery_operation_day SET status = 'DISPATCHING', version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE service_date = ? AND status = 'OPEN' AND dispatch_plan_id IS NULL
                    AND admissions_closed_at IS NOT NULL
                """, draft.serviceDate());
        if (claimed != 1) throw new DispatchConflictException("Cette journée n'est plus disponible pour valider le plan.");

        UUID planId = UUID.randomUUID();
        var config = settings.find().orElseThrow();
        var policy = new OperationalPolicySnapshot(config.pickupServiceMinutes() * 60L, 0,
                config.deliveryServiceMinutes() * 60L, config.earlyToleranceMinutes() * 60L,
                config.lateToleranceMinutes() * 60L, "SERVICE_START");
        plans.createGenerating(planId, draft.serviceDate(), actor, "manual-" + id, hash(draft), draft.hub(),
                mapper.writeValueAsString(draft), mapper.writeValueAsString(policy), draft.packages().size(), draft.couriers().size());
        plans.attachOperationPlan(draft.serviceDate(), planId, actor);
        plans.reserveDay(planId, draft.serviceDate());
        plans.reservePackages(planId, draft.packages().stream().map(DispatchSourcePackage::packageId).toList());
        plans.saveSelectedCouriers(planId, draft.couriers().stream().map(c -> new SelectedCourierSnapshot(c.courierId(),
                c.contact(), new Courier(c.courierId().toString(), c.name(), c.vehicleType(), c.maxWeightKg(),
                        c.averageSpeedKmh(), c.departureAt(), c.departure()))).toList());
        Map<UUID, UUID> collector = collectors(draft);
        Map<UUID, Delivery> distributor = draft.deliveries().stream().collect(Collectors.toMap(Delivery::packageId, Function.identity()));
        plans.savePackageOutcomes(planId, draft.packages().stream().map(p -> {
            Delivery d = distributor.get(p.packageId());
            UUID c = collector.get(p.packageId());
            return new DispatchPackageOutcome(p.packageId(), p.deliveryRequestId(), true, c, d.courierId(),
                    !c.equals(d.courierId()), null, d.order() + 1, null, null, p.recipientName(), p.faritra(),
                    p.contact(), p.amountToCollectAriary(), p.mobileMoneyPaid(), p.desiredDeliveryTime(),
                    p.pricingSnapshot(), p.effectiveWeightKg(), p.destination(), p.desiredDeliveryEndTime(), p.deliveryTimePreference());
        }).toList());
        Set<UUID> collectionCouriers = new HashSet<>(collector.values());
        Set<UUID> distributionCouriers = draft.deliveries().stream().map(Delivery::courierId).collect(Collectors.toSet());
        Set<UUID> used = new HashSet<>(collectionCouriers);
        used.addAll(distributionCouriers);
        plans.updateCourierResults(planId, draft.couriers().stream().map(c -> new DispatchCourierResult(c.courierId(),
                used.contains(c.courierId()), collectionCouriers.contains(c.courierId()), distributionCouriers.contains(c.courierId()),
                null, null, null, null, null, null, null, null)).toList());
        plans.saveStops(planId, stops(draft));
        jdbc.update("""
                UPDATE dispatch_plan SET status = 'READY', mode = 'MANUAL', assigned_package_count = ?,
                    unassigned_package_count = 0, used_courier_count = ?, completed_processing_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'GENERATING'
                """, draft.packages().size(), used.size(), planId);
        source.markAssigned(planId, draft.packages().stream().map(DispatchSourcePackage::packageId).toList());
        source.recomputeRequestStatuses(draft.packages().stream().map(DispatchSourcePackage::deliveryRequestId).toList());
        plans.markOperationDayDispatched(planId, actor);
        plans.releaseReservations(planId);
        drafts.update(draft.withLayout(version + 1, "FINALIZED", Step.REVIEW, draft.couriers(),
                draft.collections(), draft.deliveries(), planId), version);
        drafts.event(draft.serviceDate(), actor, "MANUAL_DISPATCH_VALIDATED", Map.of("draftId", id, "planId", planId));
        return reader.get(planId);
    }

    @Transactional
    public void cancel(UUID id, long version, UUID actor) {
        source.lockAdmissionGate();
        ManualDispatchDraft draft = drafts.find(id, true).orElseThrow(DispatchNotFoundException::new);
        if ("CANCELLED".equals(draft.status())) return;
        requireEditable(draft, version);
        drafts.update(draft.withLayout(version + 1, "CANCELLED", draft.step(), draft.couriers(),
                draft.collections(), draft.deliveries(), null), version);
        jdbc.update("""
                UPDATE delivery_operation_day SET admissions_closed_at = NULL, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE service_date = ? AND status = 'OPEN' AND dispatch_plan_id IS NULL
                """, draft.serviceDate());
        drafts.event(draft.serviceDate(), actor, "MANUAL_DISPATCH_DRAFT_CANCELLED", Map.of("draftId", id));
    }

    @Transactional
    public ManualDispatchDraft edit(UUID planId, long version, UUID actor) {
        source.lockAdmissionGate();
        ManualDispatchDraft previous = drafts.forPlan(planId).orElseThrow(() ->
                new DispatchInputException("Ce plan historique ne provient pas de l'éditeur manuel."));
        // Nested control transaction participates here: if start fails the old plan remains intact.
        controls.cancel(planId, version, "Réouverture dans l'éditeur manuel", actor);
        return start(previous.serviceDate(), previous.hub(), previous.couriers(), actor,
                "edit-" + UUID.randomUUID(), hash(previous), previous);
    }

    private ManualDispatchDraft editable(UUID id, long version) {
        ManualDispatchDraft draft = drafts.find(id, true).orElseThrow(DispatchNotFoundException::new);
        requireEditable(draft, version);
        return draft;
    }

    private void requireEditable(ManualDispatchDraft draft, long version) {
        if (!"DRAFT".equals(draft.status())) throw new DispatchConflictException("Ce brouillon n'est plus modifiable.");
        if (draft.version() != version) throw new DispatchConflictException("Le brouillon a changé. Rechargez-le avant de continuer.");
    }

    private List<DraftCourier> validateDepartures(ManualDispatchDraft draft, List<DraftCourier> submitted) {
        if (submitted == null || submitted.size() != draft.couriers().size()) throw new DispatchInputException("La liste des livreurs est invalide.");
        Map<UUID, DraftCourier> byId = new HashMap<>();
        for (DraftCourier c : submitted) {
            if (c == null || c.courierId() == null || byId.put(c.courierId(), c) != null) {
                throw new DispatchInputException("Un livreur est présent plusieurs fois.");
            }
        }
        return draft.couriers().stream().map(saved -> {
            DraftCourier c = byId.get(saved.courierId());
            if (c == null) throw new DispatchInputException("Un livreur du brouillon est absent.");
            coordinates(c.departure());
            if (c.departureAt() == null || !c.departureAt().atZoneSameInstant(ANTANANARIVO).toLocalDate().equals(draft.serviceDate())) {
                throw new DispatchInputException("Le départ du livreur doit avoir lieu à la date d'activité.");
            }
            return new DraftCourier(saved.courierId(), saved.name(), saved.contact(), saved.vehicleType(),
                    saved.maxWeightKg(), saved.averageSpeedKmh(), c.departureAt().atZoneSameInstant(ANTANANARIVO).toOffsetDateTime(), c.departure());
        }).toList();
    }

    static List<Pickup> groupedPickups(List<DispatchSourcePackage> packages) {
        Map<String, List<DispatchSourcePackage>> groups = new LinkedHashMap<>();
        for (DispatchSourcePackage p : packages) {
            coordinates(p.pickup());
            String key = Double.toString(p.pickup().latitude() + 0d) + ":" + Double.toString(p.pickup().longitude() + 0d);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(p);
        }
        return groups.entrySet().stream().map(e -> new Pickup(
                UUID.nameUUIDFromBytes(e.getKey().getBytes(StandardCharsets.UTF_8)).toString(), e.getValue().getFirst().pickup(),
                e.getValue().stream().map(DispatchSourcePackage::packageId).toList())).toList();
    }

    static void validateLayout(ManualDispatchDraft draft, boolean complete) {
        if (draft.step() == null || draft.collections() == null || draft.deliveries() == null) {
            throw new DispatchInputException("La répartition est incomplète.");
        }
        Set<UUID> couriers = draft.couriers().stream().map(DraftCourier::courierId).collect(Collectors.toSet());
        Set<String> pickups = draft.pickups().stream().map(Pickup::id).collect(Collectors.toSet());
        Set<UUID> packageIds = packageIds(draft);
        Set<String> seenPickups = new HashSet<>();
        Set<UUID> seenPackages = new HashSet<>();
        Map<UUID, Set<Integer>> collectionOrders = new HashMap<>(), deliveryOrders = new HashMap<>();
        for (Collection c : draft.collections()) {
            if (c == null || !pickups.contains(c.pickupId()) || !couriers.contains(c.courierId()) || !seenPickups.add(c.pickupId())) {
                throw new DispatchInputException("Une récupération est invalide ou affectée plusieurs fois.");
            }
            addOrder(collectionOrders, c.courierId(), c.order());
        }
        for (Delivery d : draft.deliveries()) {
            if (d == null || !packageIds.contains(d.packageId()) || !couriers.contains(d.courierId()) || !seenPackages.add(d.packageId())) {
                throw new DispatchInputException("Un colis est invalide ou affecté plusieurs fois.");
            }
            addOrder(deliveryOrders, d.courierId(), d.order());
        }
        for (Set<Integer> orders : java.util.stream.Stream.concat(collectionOrders.values().stream(), deliveryOrders.values().stream()).toList()) {
            for (int i = 0; i < orders.size(); i++) if (!orders.contains(i)) {
                throw new DispatchInputException("L'ordre des passages doit être continu pour chaque livreur.");
            }
        }
        if (complete && (!seenPickups.equals(pickups) || !seenPackages.equals(packageIds))) {
            throw new DispatchInputException("Affectez toutes les récupérations et tous les colis avant de valider le dispatch.");
        }
    }

    private static void addOrder(Map<UUID, Set<Integer>> orders, UUID courier, int order) {
        if (order < 0 || !orders.computeIfAbsent(courier, ignored -> new HashSet<>()).add(order)) {
            throw new DispatchInputException("Deux passages ne peuvent pas avoir le même ordre pour un livreur.");
        }
    }

    private static Set<UUID> packageIds(ManualDispatchDraft draft) {
        return draft.packages().stream().map(DispatchSourcePackage::packageId).collect(Collectors.toSet());
    }

    static Map<UUID, UUID> collectors(ManualDispatchDraft draft) {
        Map<String, UUID> assigned = draft.collections().stream().collect(Collectors.toMap(Collection::pickupId, Collection::courierId));
        Map<UUID, UUID> result = new HashMap<>();
        for (Pickup p : draft.pickups()) for (UUID id : p.packageIds()) result.put(id, assigned.get(p.id()));
        return result;
    }

    static List<DispatchStop> stops(ManualDispatchDraft draft) {
        List<DispatchStop> stops = new ArrayList<>();
        Map<String, Pickup> pickups = draft.pickups().stream().collect(Collectors.toMap(Pickup::id, Function.identity()));
        Map<UUID, DispatchSourcePackage> packages = draft.packages().stream().collect(Collectors.toMap(DispatchSourcePackage::packageId, Function.identity()));
        for (DraftCourier courier : draft.couriers()) {
            List<Collection> collections = draft.collections().stream().filter(c -> c.courierId().equals(courier.courierId())).sorted(Comparator.comparingInt(Collection::order)).toList();
            List<Delivery> deliveries = draft.deliveries().stream().filter(d -> d.courierId().equals(courier.courierId())).sorted(Comparator.comparingInt(Delivery::order)).toList();
            if (collections.isEmpty() && deliveries.isEmpty()) continue;
            int order = 0;
            stops.add(stop(courier.courierId(), order++, "START", null, null, courier.departure(), courier.departureAt().toInstant()));
            for (Collection c : collections) {
                Pickup p = pickups.get(c.pickupId());
                stops.add(stop(courier.courierId(), order++, "PICKUP", null, null, p.location(), null));
            }
            for (Delivery d : deliveries) {
                DispatchSourcePackage p = packages.get(d.packageId());
                stops.add(stop(courier.courierId(), order++, "DELIVERY", p.deliveryRequestId(), p.packageId(), p.destination(), null));
            }
        }
        return stops;
    }

    private static DispatchStop stop(UUID courier, int order, String type, UUID request, UUID pkg, Location location, Instant at) {
        return new DispatchStop(UUID.randomUUID(), courier, order, type, request, pkg, location.label(), location, at, null, null, null, null);
    }

    private static void coordinates(Location location) {
        if (location == null || location.latitude() == null || location.longitude() == null
                || !Double.isFinite(location.latitude()) || !Double.isFinite(location.longitude())
                || Math.abs(location.latitude()) > 90 || Math.abs(location.longitude()) > 180) {
            throw new DispatchInputException("Confirmez le point de départ ou de récupération sur la carte.");
        }
        if ((location.label() != null && location.label().length() > 200)
                || (location.destinationQuery() != null && location.destinationQuery().length() > 500)) {
            throw new DispatchInputException("Le libellé ou l'adresse du point est trop long.");
        }
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(value)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
