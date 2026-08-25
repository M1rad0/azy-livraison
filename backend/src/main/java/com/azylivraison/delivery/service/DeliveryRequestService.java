package com.azylivraison.delivery.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.api.AdminQueueResponse;
import com.azylivraison.delivery.api.AdminQueueStatsResponse;
import com.azylivraison.delivery.api.ConfirmedLocationRequest;
import com.azylivraison.delivery.api.CreateDeliveryPackageRequest;
import com.azylivraison.delivery.api.CreateDeliveryRequest;
import com.azylivraison.delivery.api.DeliveryRequestResponse;
import com.azylivraison.delivery.domain.ConfirmedLocation;
import com.azylivraison.delivery.domain.DeliveryPackageStatus;
import com.azylivraison.delivery.domain.DeliveryRequestStatus;
import com.azylivraison.delivery.domain.PackageWeightSource;
import com.azylivraison.delivery.domain.StoredDeliveryPackage;
import com.azylivraison.delivery.domain.StoredDeliveryRequest;
import com.azylivraison.delivery.repository.DeliveryRequestRepository;
import com.azylivraison.delivery.repository.DeliveryRequestRepository.OperationalSettingsSnapshot;
import com.azylivraison.delivery.repository.DeliveryRequestRepository.QueueCounts;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryRequestService {

    private final DeliveryRequestRepository repository;

    public DeliveryRequestService(DeliveryRequestRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DeliveryRequestResponse create(UUID ownerAccountId, CreateDeliveryRequest input) {
        OperationalSettingsSnapshot settings = repository.lockOperationalSettings();
        int current = repository.countPendingPackages(input.requestedDate());
        int requested = input.packages().size();
        int projected = Math.addExact(current, requested);
        boolean overflow = projected > settings.dailyPackageLimit();
        if (overflow && !input.acceptCapacityOverflow()) {
            throw new CapacityConfirmationRequiredException(
                    settings.dailyPackageLimit(), current, requested, projected);
        }

        Instant now = Instant.now();
        UUID requestId = UUID.randomUUID();
        ConfirmedLocation pickup = location(input.pickup());
        List<StoredDeliveryPackage> packages = new ArrayList<>(requested);
        for (int index = 0; index < requested; index++) {
            packages.add(toPackage(
                    requestId,
                    index + 1,
                    input.packages().get(index),
                    settings.defaultPackageWeightKg(),
                    now));
        }

        StoredDeliveryRequest deliveryRequest = new StoredDeliveryRequest(
                requestId,
                ownerAccountId,
                null,
                null,
                input.requestedDate(),
                pickup,
                settings.dailyPackageLimit(),
                current,
                requested,
                projected,
                overflow,
                overflow && input.acceptCapacityOverflow(),
                DeliveryRequestStatus.PENDING,
                0,
                now,
                now,
                packages);
        repository.insert(deliveryRequest, ownerAccountId);
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
        List<DeliveryRequestResponse> requests = repository.findPendingQueueThrough(date).stream()
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

    private StoredDeliveryPackage toPackage(
            UUID requestId,
            int sequence,
            CreateDeliveryPackageRequest input,
            BigDecimal defaultWeight,
            Instant now) {
        boolean providedWeight = input.weightKg() != null;
        return new StoredDeliveryPackage(
                UUID.randomUUID(),
                requestId,
                sequence,
                normalize(input.recipientName()),
                input.faritra(),
                location(input.destination()),
                normalize(input.contact()),
                input.amountToCollectAriary(),
                input.mobileMoneyPaid(),
                providedWeight ? input.weightKg() : defaultWeight,
                providedWeight ? PackageWeightSource.PROVIDED : PackageWeightSource.DEFAULT_SETTING,
                DeliveryPackageStatus.PENDING,
                null,
                0,
                now,
                now);
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
