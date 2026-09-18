package com.azylivraison.dailyworkflow.service;

import static com.azylivraison.dailyworkflow.domain.ClientClosureModels.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.dailyworkflow.domain.OperationDayStatus;
import com.azylivraison.dailyworkflow.repository.ClientClosureRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientClosureService {

    private final ClientClosureRepository repository;

    public ClientClosureService(ClientClosureRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ClientClosureOverview overview(LocalDate serviceDate) {
        if (serviceDate == null) {
            throw new DailyWorkflowInputException("Indiquez la date d'activité.");
        }
        DayState day = repository.findDay(serviceDate)
                .orElseThrow(() -> new DailyWorkflowNotFoundException(
                        "Aucune journée d'activité n'existe pour cette date."));
        var clients = repository.clientsForDate(serviceDate).stream()
                .map(client -> new ClientClosureItem(
                        client,
                        repository.validation(serviceDate, client.clientAccountId()),
                        repository.findClosure(serviceDate, client.clientAccountId()).orElse(null)))
                .toList();
        return new ClientClosureOverview(day, clients);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ClientClosureOverview close(
            LocalDate serviceDate,
            UUID clientAccountId,
            long expectedDayVersion,
            UUID actorAccountId) {
        if (serviceDate == null || clientAccountId == null || actorAccountId == null) {
            throw new DailyWorkflowInputException("La date, le client et l'administrateur sont obligatoires.");
        }
        DayState day = repository.lockDay(serviceDate)
                .orElseThrow(() -> new DailyWorkflowNotFoundException(
                        "Aucune journée d'activité n'existe pour cette date."));
        if (day.version() != expectedDayVersion) {
            throw new DailyWorkflowConflictException(
                    "La journée a été modifiée entre-temps. Rechargez la page avant de continuer.");
        }
        if (day.status() == OperationDayStatus.CLOSED) {
            throw new DailyWorkflowConflictException("Cette journée est déjà clôturée.");
        }
        if (day.status() != OperationDayStatus.OPEN && day.status() != OperationDayStatus.DISPATCHED) {
            throw new DailyWorkflowConflictException(
                    "Attendez la fin du dispatch avant de clôturer un client.");
        }
        repository.lockClosure(serviceDate, clientAccountId).ifPresent(existing -> {
            if (existing.closed()) {
                throw new DailyWorkflowConflictException(
                        "Ce client est déjà clôturé pour cette journée.");
            }
        });
        ClientCandidate client = repository.clientsForDate(serviceDate).stream()
                .filter(candidate -> candidate.clientAccountId().equals(clientAccountId))
                .findFirst()
                .orElseThrow(() -> new DailyWorkflowNotFoundException(
                        "Ce client n'a aucune demande active pour cette journée."));
        ClientValidation validation = repository.validation(serviceDate, clientAccountId);
        if (!validation.ready()) {
            throw new DailyWorkflowConflictException(
                    "La clôture de ce client est impossible : " + String.join(" ", validation.blockers()));
        }

        int nextRevision = Math.addExact(day.closureRevision(), 1);
        Instant now = Instant.now();
        if (!repository.incrementDayRevision(day, nextRevision, now)) {
            throw new DailyWorkflowConflictException(
                    "La journée a été modifiée entre-temps. Rechargez la page avant de continuer.");
        }
        repository.snapshotClient(serviceDate, nextRevision, clientAccountId, now);
        ClientClosure closure = new ClientClosure(
                serviceDate, clientAccountId, ClientClosureStatus.CLOSED, nextRevision,
                deriveReportId(serviceDate, nextRevision, clientAccountId),
                validation.packageCount(), validation.recoveredAriary(), validation.deliveryFeesAriary(),
                validation.amountToRemitAriary(), now, actorAccountId,
                null, null, null, 0, false);
        if (!repository.insertClosure(closure, now)) {
            throw new DailyWorkflowConflictException(
                    "Ce client a déjà été clôturé par une autre opération.");
        }
        if (!repository.insertSettlement(closure, client.displayName())) {
            throw new DailyWorkflowConflictException(
                    "Le relevé financier du client n'a pas pu être créé de manière cohérente.");
        }
        repository.insertClosureEvent(closure);
        return overview(serviceDate);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ClientClosureOverview reopen(
            LocalDate serviceDate,
            UUID clientAccountId,
            long expectedClosureVersion,
            String reason,
            UUID actorAccountId) {
        if (serviceDate == null || clientAccountId == null || actorAccountId == null) {
            throw new DailyWorkflowInputException("La date, le client et l'administrateur sont obligatoires.");
        }
        String normalizedReason = normalizeReason(reason);
        DayState day = repository.lockDay(serviceDate)
                .orElseThrow(() -> new DailyWorkflowNotFoundException(
                        "Aucune journée d'activité n'existe pour cette date."));
        if (day.status() == OperationDayStatus.CLOSED) {
            throw new DailyWorkflowConflictException(
                    "Rouvrez la journée pour rouvrir toutes ses clôtures clients.");
        }
        if (day.status() != OperationDayStatus.OPEN && day.status() != OperationDayStatus.DISPATCHED) {
            throw new DailyWorkflowConflictException(
                    "Une clôture client ne peut pas être rouverte pendant le dispatch.");
        }
        ClientClosure closure = repository.lockClosure(serviceDate, clientAccountId)
                .orElseThrow(() -> new DailyWorkflowNotFoundException(
                        "Ce client n'est pas clôturé pour cette journée."));
        if (!closure.closed()) {
            throw new DailyWorkflowConflictException(
                    "Cette clôture client est déjà rouverte.");
        }
        if (closure.version() != expectedClosureVersion) {
            throw new DailyWorkflowConflictException(
                    "La clôture client a été modifiée entre-temps. Rechargez la page.");
        }
        assertClosureCanReopen(closure);
        Instant now = Instant.now();
        if (!repository.reopenClosure(closure, actorAccountId, normalizedReason, now)) {
            throw new DailyWorkflowConflictException(
                    "La clôture client a été modifiée entre-temps. Rechargez la page.");
        }
        repository.touchDay(day, now);
        repository.insertReopenEvent(closure, actorAccountId, normalizedReason, now);
        return overview(serviceDate);
    }

    @Transactional
    public void reopenAllForDay(
            LocalDate serviceDate, UUID actorAccountId, String reason) {
        repository.reopenAllClosed(serviceDate, actorAccountId, normalizeReason(reason), Instant.now());
    }

    @Transactional(readOnly = true)
    public void assertAllClientsClosed(LocalDate serviceDate) {
        if (!repository.allParticipatingClientsClosed(serviceDate)) {
            throw new DailyWorkflowConflictException(
                    "Clôturez tous les clients de cette journée avant la clôture globale.");
        }
    }

    @Transactional(readOnly = true)
    public boolean isClientClosed(LocalDate serviceDate, UUID clientAccountId) {
        return serviceDate != null && clientAccountId != null
                && repository.isClientClosed(serviceDate, clientAccountId);
    }

    @Transactional(readOnly = true)
    public void assertClientOpen(LocalDate serviceDate, UUID clientAccountId) {
        if (isClientClosed(serviceDate, clientAccountId)) {
            throw new DailyWorkflowConflictException(
                    "Ce client est déjà clôturé pour cette journée. Ses informations sont désormais figées.");
        }
    }

    /** Serializes a write with a concurrent close of the same operational day. */
    @Transactional
    public void assertClientOpenForMutation(LocalDate serviceDate, UUID clientAccountId) {
        if (serviceDate == null || clientAccountId == null) return;
        repository.lockDay(serviceDate);
        assertClientOpen(serviceDate, clientAccountId);
    }

    public static UUID deriveReportId(
            LocalDate serviceDate, int closureRevision, UUID clientAccountId) {
        String identity = "AZY_CLIENT_DAILY_REPORT|" + serviceDate + "|"
                + closureRevision + "|" + clientAccountId;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private void assertClosureCanReopen(ClientClosure closure) {
        if (repository.hasConfirmedPayment(closure)) {
            throw new DailyWorkflowConflictException(
                    "Annulez d'abord les versements confirmés liés à ce rapport client.");
        }
        if (repository.hasCompensationBatch(closure)) {
            throw new DailyWorkflowConflictException(
                    "Les rémunérations des livreurs de ce client sont déjà validées.");
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DailyWorkflowInputException("Indiquez la raison de la réouverture.");
        }
        String normalized = reason.trim();
        if (normalized.length() > 1000) {
            throw new DailyWorkflowInputException(
                    "La raison de la réouverture ne peut pas dépasser 1000 caractères.");
        }
        return normalized;
    }
}
