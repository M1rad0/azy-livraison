package com.azylivraison.delivery.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import com.azylivraison.administration.service.CourierProfileService;
import com.azylivraison.delivery.api.DeliveryRequestResponse;
import com.azylivraison.delivery.api.ExpressDeliveryResponse;
import com.azylivraison.delivery.repository.DeliveryRequestRepository;
import com.azylivraison.delivery.repository.ExpressDeliveryRepository;
import com.azylivraison.delivery.repository.ExpressDeliveryRepository.CourierSnapshot;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpressDeliveryService {

    private final ExpressDeliveryRepository repository;
    private final DeliveryRequestRepository deliveryRequests;
    private final CourierProfileService couriers;

    public ExpressDeliveryService(ExpressDeliveryRepository repository,
            DeliveryRequestRepository deliveryRequests, CourierProfileService couriers) {
        this.repository = repository;
        this.deliveryRequests = deliveryRequests;
        this.couriers = couriers;
    }

    @Transactional(readOnly = true)
    public List<ExpressDeliveryResponse> list() {
        return repository.findActiveIds().stream().map(this::response).toList();
    }

    @Transactional
    public ExpressDeliveryResponse confirm(UUID requestId, List<UUID> requestedCourierIds,
            long expectedVersion, UUID actorId) {
        var state = requireState(requestId);
        if (!"PENDING".equals(state.status()) || state.cancelled())
            throw new InvalidDeliveryRequestFilterException("Cette demande express n'est plus en attente.");
        var uniqueIds = new LinkedHashSet<>(requestedCourierIds);
        if (uniqueIds.size() != requestedCourierIds.size())
            throw new InvalidDeliveryRequestFilterException("Un livreur ne peut etre selectionne qu'une fois.");
        var available = couriers.findAll().stream().filter(item -> item.courier().active())
                .filter(item -> uniqueIds.contains(item.courier().id())).toList();
        if (available.size() != uniqueIds.size())
            throw new InvalidDeliveryRequestFilterException("Un des livreurs selectionnes est introuvable ou inactif.");
        List<CourierSnapshot> snapshots = available.stream().map(item -> new CourierSnapshot(
                item.courier().id(), item.courier().name(), item.maxWeightKg())).toList();
        if (!repository.confirm(requestId, expectedVersion, snapshots, actorId, Instant.now()))
            throw new InvalidDeliveryRequestFilterException("La demande express a ete modifiee entre-temps.");
        return response(requestId);
    }

    @Transactional
    public void cancel(UUID requestId, long expectedVersion, UUID actorId) {
        requireState(requestId);
        if (!repository.cancelPending(requestId, expectedVersion, actorId, Instant.now()))
            throw new InvalidDeliveryRequestFilterException(
                    "Seule une demande express encore en attente peut etre annulee.");
    }

    private ExpressDeliveryResponse response(UUID id) {
        DeliveryRequestResponse request = deliveryRequests.findById(id)
                .map(DeliveryRequestResponse::from)
                .orElseThrow(() -> new DeliveryRequestNotFoundException(id));
        var selected = repository.couriers(id);
        BigDecimal totalCapacity = selected.stream().map(CourierSnapshot::id)
                .map(courierId -> couriers.findById(courierId).maxWeightKg())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWeight = request.packages().stream().map(item -> item.effectiveWeightKg())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ExpressDeliveryResponse(request,
                selected.stream().map(item -> new ExpressDeliveryResponse.Courier(item.id(), item.name())).toList(),
                totalWeight, totalCapacity,
                !selected.isEmpty() && totalWeight.compareTo(totalCapacity) > 0,
                repository.confirmedAt(id));
    }

    private ExpressDeliveryRepository.ExpressState requireState(UUID id) {
        var state = repository.lock(id);
        if (state == null) throw new DeliveryRequestNotFoundException(id);
        return state;
    }
}
