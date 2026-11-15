package com.azylivraison.delivery.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.api.AdminQueueResponse;
import com.azylivraison.delivery.api.AdminQueueStatsResponse;
import com.azylivraison.delivery.api.ConfirmedLocationRequest;
import com.azylivraison.delivery.api.CreateDeliveryPackageRequest;
import com.azylivraison.delivery.api.CreateDeliveryRequest;
import com.azylivraison.delivery.api.DeliveryRequestResponse;
import com.azylivraison.delivery.api.UpdateDeliveryPackageRequest;
import com.azylivraison.delivery.api.RescheduleDeliveryPackageRequest;
import com.azylivraison.delivery.domain.ConfirmedLocation;
import com.azylivraison.delivery.domain.DeliveryPackageStatus;
import com.azylivraison.delivery.domain.DeliveryRequestStatus;
import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.delivery.domain.ExpressDeliveryStatus;
import com.azylivraison.delivery.domain.PackageWeightSource;
import com.azylivraison.delivery.domain.StoredDeliveryPackage;
import com.azylivraison.delivery.domain.StoredDeliveryRequest;
import com.azylivraison.delivery.domain.DeliveryTimePreference;
import com.azylivraison.delivery.domain.DeliveryTimeWindow;
import com.azylivraison.dispatch.service.ManualDispatchService;
import com.azylivraison.delivery.repository.DeliveryRequestRepository;
import com.azylivraison.delivery.repository.DeliveryRequestRepository.OperationalSettingsSnapshot;
import com.azylivraison.delivery.repository.DeliveryRequestRepository.QueueCounts;
import com.azylivraison.pricing.domain.AdvancedPricingPolicy;
import com.azylivraison.pricing.domain.AdvancedPricingQuote;
import com.azylivraison.pricing.service.AdvancedPricingService;
import com.azylivraison.pricing.service.PricingConfigurationException;
import com.azylivraison.dailyworkflow.service.ClientClosureService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryRequestService {

    private static final ZoneId ACTIVITY_ZONE = ZoneId.of("Indian/Antananarivo");

    private final DeliveryRequestRepository repository;
    private final AdvancedPricingService pricingService;
    private final ClientClosureService clientClosures;
    private final ManualDispatchService manualDispatch;

    public DeliveryRequestService(
            DeliveryRequestRepository repository,
            AdvancedPricingService pricingService,
            ClientClosureService clientClosures,
            ManualDispatchService manualDispatch) {
        this.repository = repository;
        this.pricingService = pricingService;
        this.clientClosures = clientClosures;
        this.manualDispatch = manualDispatch;
    }

    @Transactional
    public DeliveryRequestResponse create(UUID ownerAccountId, CreateDeliveryRequest input) {
        if (input.requestKind() == DeliveryRequestKind.EXPRESS
                && !repository.canCreateExpress(ownerAccountId)) {
            throw new ExpressDeliveryForbiddenException();
        }
        return create(ownerAccountId, ownerAccountId, input);
    }

    @Transactional
    public DeliveryRequestResponse createForAdministration(
            UUID ownerAccountId, UUID administratorAccountId, CreateDeliveryRequest input) {
        return create(ownerAccountId, administratorAccountId, input);
    }

    private DeliveryRequestResponse create(
            UUID ownerAccountId, UUID actorAccountId, CreateDeliveryRequest input) {
        OperationalSettingsSnapshot settings = repository.lockOperationalSettings();
        boolean express = input.requestKind() == DeliveryRequestKind.EXPRESS;
        LocalDate today = LocalDate.now(ACTIVITY_ZONE);
        if (!express && input.requestedDate() == null) {
            throw new InvalidDeliveryRequestException(
                    "Indiquez la date souhaitée pour cette demande.");
        }
        if (!express && input.requestedDate().isBefore(today)) {
            throw new InvalidDeliveryRequestException(
                    "La date de livraison ne peut pas être passée.");
        }
        LocalDate requestedDate = express
                ? today
                : input.requestedDate();
        clientClosures.assertClientOpenForMutation(requestedDate, ownerAccountId);
        validateDesiredDeliveryTimes(input.packages(), express);
        if (!express && repository.isDateClosedForAdmissions(requestedDate)) {
            throw new DeliveryDateClosedException(requestedDate);
        }
        int current = express ? 0 : repository.countAllocatedPackages(requestedDate);
        int requested = input.packages().size();
        int projected = Math.addExact(current, requested);
        boolean overflow = !express && projected > settings.dailyPackageLimit();
        if (overflow) {
            throw new DailyCapacityExceededException(
                    requestedDate, settings.dailyPackageLimit(), current, requested, projected);
        }

        Instant now = Instant.now();
        UUID requestId = UUID.randomUUID();
        ConfirmedLocation pickup = location(input.pickup());
        AdvancedPricingPolicy pricingPolicy = pricingService.effectivePolicy(ownerAccountId, input.requestKind());
        List<StoredDeliveryPackage> packages = new ArrayList<>(requested);
        for (int index = 0; index < requested; index++) {
            packages.add(toPackage(
                    requestId,
                    index + 1,
                    input.packages().get(index),
                    pricingPolicy,
                    now));
        }

        StoredDeliveryRequest deliveryRequest = new StoredDeliveryRequest(
                requestId,
                ownerAccountId,
                null,
                null,
                input.requestKind(),
                express ? ExpressDeliveryStatus.PENDING : null,
                actorAccountId,
                requestedDate,
                pickup,
                settings.dailyPackageLimit(),
                current,
                requested,
                projected,
                false,
                false,
                DeliveryRequestStatus.PENDING,
                0,
                now,
                now,
                packages);
        repository.insert(deliveryRequest, actorAccountId);
        if (input.saveAsDefaultPickup()) {
            repository.saveDefaultPickup(ownerAccountId, pickup, now);
        }
        return repository.findByIdAndOwner(requestId, ownerAccountId)
                .map(DeliveryRequestResponse::from)
                .orElseThrow(() -> new DeliveryRequestNotFoundException(requestId));
    }

    @Transactional(readOnly = true)
    public List<DeliveryRequestResponse> findForOwner(UUID ownerAccountId) {
        return repository.findByOwner(ownerAccountId).stream()
                .map(DeliveryRequestResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeliveryRequestResponse findForOwner(UUID id, UUID ownerAccountId) {
        return repository.findByIdAndOwner(id, ownerAccountId)
                .map(DeliveryRequestResponse::from)
                .orElseThrow(() -> new DeliveryRequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<DeliveryRequestResponse> findForAdministration(
            LocalDate date,
            LocalDate from,
            LocalDate to,
            DeliveryRequestStatus status,
            String clientQuery,
            boolean pendingOnly) {
        validateAdministrationFilters(date, from, to);
        return repository.findForAdministration(date, from, to, status, clientQuery, pendingOnly).stream()
                .map(pendingOnly
                        ? DeliveryRequestResponse::fromPendingPackages
                        : DeliveryRequestResponse::from)
                .toList();
    }

    private void validateAdministrationFilters(LocalDate date, LocalDate from, LocalDate to) {
        if (date != null && (from != null || to != null)) {
            throw new InvalidDeliveryRequestFilterException(
                    "Le filtre date ne peut pas être combiné avec une période.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidDeliveryRequestFilterException(
                    "La date de début doit précéder ou être égale à la date de fin.");
        }
    }

    @Transactional(readOnly = true)
    public DeliveryRequestResponse findForAdministration(UUID id) {
        return repository.findById(id)
                .map(DeliveryRequestResponse::from)
                .orElseThrow(() -> new DeliveryRequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public AdminQueueResponse queue(LocalDate date) {
        OperationalSettingsSnapshot settings = repository.getOperationalSettings();
        QueueCounts counts = repository.queueCounts(date);
        List<DeliveryRequestResponse> requests = repository.findPendingQueueForDate(date).stream()
                .map(DeliveryRequestResponse::fromPendingPackages)
                .toList();
        int remaining = Math.max(0, settings.dailyPackageLimit() - counts.pendingPackageCount());
        return new AdminQueueResponse(
                date,
                new AdminQueueStatsResponse(
                        settings.dailyPackageLimit(),
                        counts.pendingRequestCount(),
                        counts.partiallyDispatchedRequestCount(),
                        counts.pendingPackageCount(),
                        remaining,
                        counts.pendingPackageCount() > settings.dailyPackageLimit()),
                requests);
    }

    @Transactional
    public DeliveryRequestResponse updatePackage(UUID packageId, UUID ownerAccountId,
            UUID actorId, UpdateDeliveryPackageRequest input) {
        repository.lockOperationalSettings(); // shared admission gate: always first
        StoredDeliveryRequest request = mutableRequest(packageId, ownerAccountId, input.expectedVersion());
        StoredDeliveryPackage source = packageIn(request, packageId);
        CreateDeliveryPackageRequest packageInput = input.packageInput();
        validateDesiredDeliveryTimes(List.of(packageInput), request.requestKind() == DeliveryRequestKind.EXPRESS);
        DeliveryTimeWindow window = window(packageInput);
        ConfirmedLocation destination = location(packageInput.destination());
        boolean recalculate = !destination.equals(source.destination())
                || !java.util.Objects.equals(window.start(), source.desiredDeliveryTime())
                || !java.util.Objects.equals(window.end(), source.desiredDeliveryEndTime())
                || window.preference() != source.deliveryTimePreference()
                || (input.weightKg() == null ? source.weightSource() != PackageWeightSource.DEFAULT_SETTING
                    : source.weightSource() != PackageWeightSource.PROVIDED
                        || input.weightKg().compareTo(source.effectiveWeightKg()) != 0);
        Instant now = Instant.now();
        StoredDeliveryPackage calculated = recalculate
                ? toPackage(request.id(), source.sequenceNumber(), packageInput,
                    pricingService.effectivePolicy(request.ownerAccountId(), request.requestKind()), now) : source;
        StoredDeliveryPackage edited = new StoredDeliveryPackage(
                source.id(), source.deliveryRequestId(), source.sequenceNumber(), normalize(input.recipientName()),
                input.faritra(), destination, normalize(input.contact()), input.amountToCollectAriary(),
                input.mobileMoneyPaid(), window.start(), calculated.pricingSnapshot(),
                source.deliveryFeeOverriddenAt() != null ? source.finalDeliveryFeeAriary() : calculated.finalDeliveryFeeAriary(),
                source.deliveryFeeOverrideReason(), source.deliveryFeeOverriddenAt(), source.deliveryFeeOverriddenByAccountId(),
                source.deliveryFeeVersion(), calculated.effectiveWeightKg(), calculated.weightSource(), source.status(),
                source.unassignedReason(), source.finalOutcome(), source.actualCollectedAriary(), source.outcomeNote(),
                source.outcomeRecordedAt(), source.outcomeRecordedByAccountId(), source.returnCompletedAt(),
                source.returnCompletedByAccountId(), source.carriedForwardFromPackageId(), source.dispatchPlanId(),
                source.priorityAt(), source.version(), source.createdAt(), now,
                window.end(), window.preference(), true);
        repository.updatePackage(source, edited, actorId, now);
        if (input.pickup() != null) {
            if (request.packages().stream().anyMatch(item -> !item.canEdit())) {
                throw new DeliveryPackageConflictException("Le point de retrait de cette demande n'est plus modifiable.");
            }
            ConfirmedLocation pickup = location(input.pickup());
            if (!pickup.equals(request.pickup())) repository.updatePickup(request.id(), packageId, pickup, now);
        }
        if (request.requestKind() == DeliveryRequestKind.NORMAL)
            manualDispatch.synchronizePendingPackages(request.requestedDate(), actorId);
        return repository.findById(request.id()).map(DeliveryRequestResponse::from)
                .orElseThrow(() -> new DeliveryRequestNotFoundException(request.id()));
    }

    @Transactional
    public DeliveryRequestResponse reschedulePackage(UUID packageId, UUID ownerAccountId,
            UUID actorId, RescheduleDeliveryPackageRequest input) {
        OperationalSettingsSnapshot settings = repository.lockOperationalSettings();
        StoredDeliveryRequest request = mutableRequest(packageId, ownerAccountId, input.expectedVersion());
        StoredDeliveryPackage source = packageIn(request, packageId);
        LocalDate target = input.requestedDate();
        LocalDate today = LocalDate.now(ACTIVITY_ZONE);
        if (target.isBefore(today) || target.equals(request.requestedDate())
                || (request.requestKind() == DeliveryRequestKind.EXPRESS && !target.isAfter(today))) {
            throw new InvalidDeliveryRequestException("Choisissez une nouvelle date non passee (future pour un express reprogramme en normal).");
        }
        clientClosures.assertClientOpenForMutation(target, request.ownerAccountId());
        if (!repository.isDateAvailableForReschedule(target)) throw new DeliveryDateClosedException(target);
        int current = repository.countAllocatedPackages(target);
        int projected = Math.addExact(current, 1);
        if (projected > settings.dailyPackageLimit()) throw new DailyCapacityExceededException(
                target, settings.dailyPackageLimit(), current, 1, projected);
        UUID newRequestId = UUID.randomUUID();
        UUID newPackageId = UUID.randomUUID();
        Instant now = Instant.now();
        StoredDeliveryPackage clone = new StoredDeliveryPackage(
                newPackageId, newRequestId, 1, source.recipientName(), source.faritra(), source.destination(),
                source.contact(), source.amountToCollectAriary(), source.mobileMoneyPaid(), source.desiredDeliveryTime(),
                source.pricingSnapshot(), source.finalDeliveryFeeAriary(), source.deliveryFeeOverrideReason(),
                source.deliveryFeeOverriddenAt(), source.deliveryFeeOverriddenByAccountId(), source.deliveryFeeVersion(),
                source.effectiveWeightKg(), source.weightSource(), DeliveryPackageStatus.PENDING, null,
                null, null, null, null, null, null, null, source.id(), null, source.priorityAt(), 0, now, now,
                source.desiredDeliveryEndTime(), source.deliveryTimePreference(), true);
        StoredDeliveryRequest rescheduled = new StoredDeliveryRequest(
                newRequestId, request.ownerAccountId(), null, null, DeliveryRequestKind.NORMAL, null, actorId, target,
                input.pickup() == null ? request.pickup() : location(input.pickup()),
                settings.dailyPackageLimit(), current, 1, projected, false, false, DeliveryRequestStatus.PENDING,
                0, now, now, List.of(clone));
        repository.insert(rescheduled, actorId);
        repository.retireRescheduledPackage(source, newRequestId, newPackageId, target, actorId, now);
        if (request.requestKind() == DeliveryRequestKind.NORMAL)
            manualDispatch.synchronizePendingPackages(request.requestedDate(), actorId);
        manualDispatch.synchronizePendingPackages(target, actorId);
        return repository.findById(newRequestId).map(DeliveryRequestResponse::from)
                .orElseThrow(() -> new DeliveryRequestNotFoundException(newRequestId));
    }

    private StoredDeliveryRequest mutableRequest(UUID packageId, UUID ownerAccountId, long expectedVersion) {
        StoredDeliveryRequest request = repository.lockRequestForPackage(packageId, ownerAccountId)
                .orElseThrow(() -> new DeliveryRequestNotFoundException(packageId));
        clientClosures.assertClientOpenForMutation(request.requestedDate(), request.ownerAccountId());
        // Read again after the day lock so a concurrent closure cannot leave a stale capability.
        request = repository.findById(request.id()).orElseThrow(() -> new DeliveryRequestNotFoundException(packageId));
        StoredDeliveryPackage source = packageIn(request, packageId);
        if (source.version() != expectedVersion) throw new DeliveryPackageConflictException(
                "Ce colis a ete modifie entre-temps. Rechargez la page avant de continuer.");
        if (!source.canEdit()) throw new DeliveryPackageConflictException(
                "Ce colis n'est plus modifiable apres validation du dispatch ou confirmation express.");
        return request;
    }

    private StoredDeliveryPackage packageIn(StoredDeliveryRequest request, UUID id) {
        return request.packages().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new DeliveryRequestNotFoundException(id));
    }

    private StoredDeliveryPackage toPackage(
            UUID requestId,
            int sequence,
            CreateDeliveryPackageRequest input,
            AdvancedPricingPolicy pricingPolicy,
            Instant now) {
        boolean providedWeight = input.weightKg() != null;
        ConfirmedLocation destination = location(input.destination());
        DeliveryTimeWindow window = window(input);
        AdvancedPricingQuote pricing = pricingService.quote(
                pricingPolicy, destination.latitude(), destination.longitude(), input.weightKg(),
                window.start(), window.end(), window.preference(), destination.label(), destination.query());
        return new StoredDeliveryPackage(
                UUID.randomUUID(),
                requestId,
                sequence,
                normalize(input.recipientName()),
                input.faritra(),
                destination,
                normalize(input.contact()),
                input.amountToCollectAriary(),
                input.mobileMoneyPaid(),
                window.start(),
                pricing.toLegacySnapshot(),
                pricing.deliveryFeeAriary(),
                null,
                null,
                null,
                0,
                pricing.effectiveWeightKg(),
                providedWeight ? PackageWeightSource.PROVIDED : PackageWeightSource.DEFAULT_SETTING,
                DeliveryPackageStatus.PENDING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                0,
                now,
                now,
                window.end(), window.preference(), true);
    }

    private void validateDesiredDeliveryTimes(
            List<CreateDeliveryPackageRequest> packages,
            boolean express) {
        LocalTime currentTime = LocalTime.now(ACTIVITY_ZONE);
        for (CreateDeliveryPackageRequest deliveryPackage : packages) {
            DeliveryTimeWindow window = window(deliveryPackage);
            LocalTime desiredTime = window.start();
            boolean period = window.preference() == DeliveryTimePreference.MORNING
                    || window.preference() == DeliveryTimePreference.AFTERNOON;
            if (express && desiredTime != null
                    && !(period ? window.end().isAfter(currentTime) : desiredTime.isAfter(currentTime))) {
                throw new PricingConfigurationException(
                        "L'heure de livraison souhaitée d'une demande express doit être ultérieure à l'heure actuelle.");
            }
        }
    }

    private DeliveryTimeWindow window(CreateDeliveryPackageRequest input) {
        return DeliveryTimeWindow.normalize(input.desiredDeliveryTime(), input.desiredDeliveryEndTime(),
                input.deliveryTimePreference());
    }

    private ConfirmedLocation location(ConfirmedLocationRequest input) {
        return new ConfirmedLocation(
                normalize(input.label()),
                normalizeNullable(input.query()),
                input.latitude(),
                input.longitude());
    }

    private String normalize(String value) {
        return value.trim();
    }

    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
