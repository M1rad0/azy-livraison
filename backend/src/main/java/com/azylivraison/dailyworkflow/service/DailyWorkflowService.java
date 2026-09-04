package com.azylivraison.dailyworkflow.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.dailyworkflow.domain.DailyOperationDetails;
import com.azylivraison.dailyworkflow.domain.DeliveryFeeMode;
import com.azylivraison.dailyworkflow.domain.OperationDay;
import com.azylivraison.dailyworkflow.domain.OperationDayStatus;
import com.azylivraison.dailyworkflow.repository.DailyWorkflowRepository;
import com.azylivraison.dailyworkflow.repository.DailyWorkflowRepository.CarryForwardIds;
import com.azylivraison.dailyworkflow.repository.DailyWorkflowRepository.CourierSnapshot;
import com.azylivraison.dailyworkflow.repository.DailyWorkflowRepository.DeliveryFeeRecord;
import com.azylivraison.dailyworkflow.repository.DailyWorkflowRepository.PackageForUpdate;
import com.azylivraison.dailyworkflow.repository.DailyWorkflowRepository.SettingsSnapshot;
import com.azylivraison.delivery.domain.ConfirmedLocation;
import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.delivery.domain.DeliveryPackageStatus;
import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.delivery.repository.ExpressDeliveryRepository;
import com.azylivraison.delivery.service.DailyCapacityExceededException;
import com.azylivraison.delivery.service.DeliveryDateClosedException;
import com.azylivraison.finance.service.FinancialSettlementService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyWorkflowService {

    private final DailyWorkflowRepository repository;
    private final FinancialSettlementService financialSettlementService;
    private final ExpressDeliveryRepository expressDeliveries;

    public DailyWorkflowService(
            DailyWorkflowRepository repository,
            FinancialSettlementService financialSettlementService,
            ExpressDeliveryRepository expressDeliveries) {
        this.repository = repository;
        this.financialSettlementService = financialSettlementService;
        this.expressDeliveries = expressDeliveries;
    }

    @Transactional(readOnly = true)
    public DailyOperationDetails get(LocalDate serviceDate) {
        OperationDay day = repository.findDay(serviceDate).orElseGet(() -> OperationDay.open(serviceDate));
        return DailyOperationDetails.of(
                day,
                day.dispatchPlanId() == null ? java.util.List.of() : repository.findPackages(day));
    }

    @Transactional
    public void cancelForClient(UUID packageId, long expectedVersion, UUID clientAccountId) {
        cancel(packageId, expectedVersion, clientAccountId, clientAccountId);
    }

    @Transactional
    public void cancelForAdministration(UUID packageId, long expectedVersion, UUID actorAccountId) {
        cancel(packageId, expectedVersion, null, actorAccountId);
    }

    @Transactional
    public DeliveryFeeRecord updateDeliveryFee(
            UUID packageId,
            DeliveryFeeMode mode,
            Long manualFeeAriary,
            String reason,
            long expectedVersion,
            UUID actorAccountId) {
        if (mode == null) {
            throw new DailyWorkflowInputException("Choisissez le tarif calculé ou un tarif manuel.");
        }
        PackageForUpdate deliveryPackage = requirePackage(packageId);
        requireVersion(expectedVersion, deliveryPackage.version(), "Le colis a été modifié entre-temps.");
        LocalDate operationDate = deliveryPackage.dispatchPlanId() == null
                ? deliveryPackage.requestedDate()
                : repository.serviceDate(deliveryPackage.dispatchPlanId());
        repository.findDay(operationDate).ifPresent(day -> {
            if (day.status() == OperationDayStatus.CLOSED) {
                throw new DailyWorkflowConflictException(
                        "Rouvrez la journée avant de corriger les frais de livraison.");
            }
        });
        Long finalFee;
        String normalizedReason = normalizeNullable(reason);
        if (mode == DeliveryFeeMode.CALCULATED) {
            if (deliveryPackage.deliveryFeeAriary() == null) {
                throw new DailyWorkflowInputException(
                        "Aucun tarif calculé n'est disponible pour ce colis. Indiquez un tarif manuel.");
            }
            finalFee = deliveryPackage.deliveryFeeAriary();
        } else {
            if (manualFeeAriary == null || manualFeeAriary < 0) {
                throw new DailyWorkflowInputException("Indiquez un tarif manuel valide en ariary.");
            }
            if (normalizedReason == null) {
                throw new DailyWorkflowInputException("Expliquez pourquoi le tarif annoncé doit être corrigé.");
            }
            finalFee = manualFeeAriary;
        }
        return repository.updateDeliveryFee(
                deliveryPackage, mode, finalFee, normalizedReason, actorAccountId, Instant.now());
    }

    @Transactional
    public DailyOperationDetails recordOutcome(
            LocalDate serviceDate,
            UUID packageId,
            DeliveryPackageOutcome outcome,
            Long actualCollectedAriary,
            UUID actualCourierId,
            String note,
            long expectedVersion,
            UUID actorAccountId) {
        if (outcome == null) {
            throw new DailyWorkflowInputException(
                    "Choisissez Livré, Reporté ou À retourner pour le compte rendu.");
        }
        OperationDay day = requireEditableDay(serviceDate);
        PackageForUpdate deliveryPackage = requirePackage(packageId);
        requirePackageInDay(day, deliveryPackage);
        requireVersion(expectedVersion, deliveryPackage.version(), "Le colis a été modifié entre-temps.");
        if (deliveryPackage.technicalStatus() != DeliveryPackageStatus.DISPATCHED) {
            throw new DailyWorkflowConflictException("Ce colis ne fait pas partie du plan dispatché.");
        }
        if (deliveryPackage.requestKind() == DeliveryRequestKind.EXPRESS
                && outcome == DeliveryPackageOutcome.POSTPONED) {
            throw new DailyWorkflowInputException(
                    "Une livraison express ne peut pas être reportée au lendemain.");
        }
        if (outcome != DeliveryPackageOutcome.POSTPONED
                && repository.hasCarriedForwardSuccessor(packageId)) {
            throw new DailyWorkflowConflictException(
                    "Ce report a déjà créé le colis du lendemain. Son état ne peut plus être remplacé.");
        }
        Long recovered = switch (outcome) {
            case DELIVERED -> actualCollectedAriary == null
                    ? deliveryPackage.amountToCollectAriary()
                    : actualCollectedAriary;
            case RETURN_TO_SENDER -> actualCollectedAriary == null ? 0L : actualCollectedAriary;
            case POSTPONED -> null;
            case CANCELLED -> null;
        };
        if ((outcome == DeliveryPackageOutcome.DELIVERED
                || outcome == DeliveryPackageOutcome.RETURN_TO_SENDER)
                && deliveryPackage.finalDeliveryFeeAriary() == null) {
            throw new DailyWorkflowConflictException(
                    "Le tarif de livraison de ce colis est absent. Corrigez-le avant la clôture.");
        }
        boolean actualCourierRequired = outcome == DeliveryPackageOutcome.DELIVERED
                || outcome == DeliveryPackageOutcome.RETURN_TO_SENDER;
        if (actualCourierRequired && actualCourierId == null) {
            throw new DailyWorkflowInputException(
                    "Indiquez le livreur réellement intervenu pour ce colis.");
        }
        if (!actualCourierRequired && actualCourierId != null) {
            throw new DailyWorkflowInputException(
                    "Un colis reporté ne doit pas avoir de livreur réel pour cette journée.");
        }
        CourierSnapshot actualCourier = null;
        if (actualCourierId != null) {
            if (actualCourierId.equals(deliveryPackage.actualCourierId())) {
                actualCourier = new CourierSnapshot(
                        deliveryPackage.actualCourierId(),
                        deliveryPackage.actualCourierName(),
                        deliveryPackage.actualCourierContact(),
                        deliveryPackage.actualCourierVehicleType());
            } else {
                actualCourier = repository.findCourierSnapshot(actualCourierId)
                        .orElseThrow(() -> new DailyWorkflowInputException(
                                "Le livreur réellement intervenu est introuvable."));
            }
        }
        repository.updateOutcome(
                deliveryPackage,
                outcome,
                recovered,
                actualCourier,
                normalizeNullable(note),
                actorAccountId,
                Instant.now());
        if (deliveryPackage.requestKind() == DeliveryRequestKind.EXPRESS) {
            expressDeliveries.completeIfFinal(deliveryPackage.deliveryRequestId());
        }
        return currentDetails(serviceDate);
    }

    @Transactional
    public DailyOperationDetails markReturnCompleted(
            LocalDate serviceDate,
            UUID packageId,
            long expectedVersion,
            UUID actorAccountId) {
        OperationDay day = repository.lockDay(serviceDate)
                .orElseThrow(() -> new DailyWorkflowNotFoundException("Cette journée est introuvable."));
        if (day.status() != OperationDayStatus.DISPATCHED && day.status() != OperationDayStatus.CLOSED) {
            throw new DailyWorkflowConflictException(
                    "Le retour ne peut être confirmé qu'après le dispatch.");
        }
        PackageForUpdate deliveryPackage = requirePackage(packageId);
        requirePackageInDay(day, deliveryPackage);
        requireVersion(expectedVersion, deliveryPackage.version(), "Le colis a été modifié entre-temps.");
        if (deliveryPackage.finalOutcome() != DeliveryPackageOutcome.RETURN_TO_SENDER) {
            throw new DailyWorkflowConflictException("Ce colis n'est pas marqué À retourner.");
        }
        if (deliveryPackage.returnCompletedAt() != null) {
            throw new DailyWorkflowConflictException("Le retour de ce colis a déjà été confirmé.");
        }
        repository.markReturnCompleted(deliveryPackage, actorAccountId, Instant.now());
        return currentDetails(serviceDate);
    }

    @Transactional
    public CarryForwardIds carryForward(
            LocalDate serviceDate,
            UUID packageId,
            ConfirmedLocation newPickup,
            long expectedVersion,
            UUID actorAccountId) {
        repository.lockAdmissionGate();
        OperationDay sourceDay = repository.lockDay(serviceDate)
                .orElseThrow(() -> new DailyWorkflowNotFoundException("Cette journée est introuvable."));
        PackageForUpdate source = requirePackage(packageId);
        requirePackageInDay(sourceDay, source);
        requireVersion(expectedVersion, source.version(), "Le colis a été modifié entre-temps.");
        if (source.finalOutcome() != DeliveryPackageOutcome.POSTPONED) {
            throw new DailyWorkflowConflictException("Seul un colis reporté peut être programmé au lendemain.");
        }
        if (repository.hasCarriedForwardSuccessor(packageId)) {
            throw new DailyWorkflowConflictException("Ce colis a déjà été transmis au jour suivant.");
        }
        LocalDate targetDate = serviceDate.plusDays(1);
        if (repository.isAdmissionsClosed(targetDate)) {
            throw new DeliveryDateClosedException(targetDate);
        }
        SettingsSnapshot settings = repository.settings();
        int current = repository.countAllocatedPackages(targetDate);
        int projected = Math.addExact(current, 1);
        if (projected > settings.dailyPackageLimit()) {
            throw new DailyCapacityExceededException(
                    targetDate, settings.dailyPackageLimit(), current, 1, projected);
        }
        return repository.insertCarryForward(
                source, newPickup, targetDate, settings, current, actorAccountId, Instant.now());
    }

    @Transactional
    public DailyOperationDetails close(LocalDate serviceDate, long expectedVersion, UUID actorAccountId) {
        OperationDay day = repository.lockDay(serviceDate)
                .orElseThrow(() -> new DailyWorkflowNotFoundException("Aucun plan n'existe pour cette date."));
        requireVersion(expectedVersion, day.version(), "La journée a été modifiée entre-temps.");
        if (day.status() != OperationDayStatus.DISPATCHED || day.dispatchPlanId() == null) {
            throw new DailyWorkflowConflictException(
                    "La journée doit être dispatchée avant de pouvoir être clôturée.");
        }
        int pendingExpress = repository.pendingExpressRequestCount(serviceDate);
        if (pendingExpress > 0) {
            throw new DailyWorkflowConflictException(
                    "Confirmez ou annulez les " + pendingExpress
                            + " demande" + (pendingExpress > 1 ? "s expresses" : " express")
                            + " encore en attente avant la clôture.");
        }
        int incomplete = repository.incompleteDayPackageCount(serviceDate);
        if (incomplete > 0) {
            throw new DailyWorkflowConflictException(
                    "Attribuez un état final aux " + incomplete
                            + " colis restant" + (incomplete > 1 ? "s" : "") + " avant la clôture.");
        }
        int missingActualCouriers = repository.missingActualCourierDayPackageCount(serviceDate);
        if (missingActualCouriers > 0) {
            throw new DailyWorkflowConflictException(
                    "Indiquez le livreur réellement intervenu pour " + missingActualCouriers
                            + " colis facturé" + (missingActualCouriers > 1 ? "s" : "")
                            + " avant la clôture.");
        }
        int notCarried = repository.uncarriedPostponedDayPackageCount(serviceDate);
        if (notCarried > 0) {
            throw new DailyWorkflowConflictException(
                    "Transmettez au lendemain "
                            + (notCarried == 1 ? "le colis reporté" : "les " + notCarried + " colis reportés")
                            + " avant la clôture.");
        }
        repository.close(day, actorAccountId, Instant.now());
        financialSettlementService.materializeClientSettlements(serviceDate);
        return currentDetails(serviceDate);
    }

    @Transactional
    public DailyOperationDetails reopen(
            LocalDate serviceDate,
            long expectedVersion,
            String reason,
            UUID actorAccountId) {
        OperationDay day = repository.lockDay(serviceDate)
                .orElseThrow(() -> new DailyWorkflowNotFoundException("Cette journée est introuvable."));
        requireVersion(expectedVersion, day.version(), "La journée a été modifiée entre-temps.");
        if (day.status() != OperationDayStatus.CLOSED) {
            throw new DailyWorkflowConflictException("Seule une journée clôturée peut être rouverte.");
        }
        String normalizedReason = normalizeNullable(reason);
        if (normalizedReason == null) {
            throw new DailyWorkflowInputException("Indiquez la raison de la réouverture.");
        }
        financialSettlementService.assertDayCanReopen(serviceDate);
        repository.reopen(day, actorAccountId, normalizedReason, Instant.now());
        return currentDetails(serviceDate);
    }

    private void cancel(
            UUID packageId,
            long expectedVersion,
            UUID requiredOwnerAccountId,
            UUID actorAccountId) {
        repository.lockAdmissionGate();
        PackageForUpdate deliveryPackage = requirePackage(packageId);
        if (requiredOwnerAccountId != null
                && !requiredOwnerAccountId.equals(deliveryPackage.ownerAccountId())) {
            throw new DailyWorkflowNotFoundException("Ce colis est introuvable.");
        }
        requireVersion(expectedVersion, deliveryPackage.version(), "Le colis a été modifié entre-temps.");
        if (deliveryPackage.technicalStatus() != DeliveryPackageStatus.PENDING
                || deliveryPackage.finalOutcome() != null) {
            throw new DailyWorkflowConflictException("Seul un colis encore en attente peut être annulé.");
        }
        if (repository.isAdmissionsClosed(deliveryPackage.requestedDate())) {
            throw new DailyWorkflowConflictException(
                    "Le plan de cette date est déjà en préparation ou enregistré.");
        }
        repository.cancelPackage(deliveryPackage, actorAccountId, Instant.now());
    }

    private OperationDay requireEditableDay(LocalDate serviceDate) {
        OperationDay day = repository.lockDay(serviceDate)
                .orElseThrow(() -> new DailyWorkflowNotFoundException("Cette journée est introuvable."));
        if (day.status() == OperationDayStatus.CLOSED) {
            throw new DailyWorkflowConflictException(
                    "Rouvrez la journée avant de corriger le compte rendu.");
        }
        if (day.status() != OperationDayStatus.DISPATCHED) {
            throw new DailyWorkflowConflictException(
                    "Le compte rendu est disponible après la génération du plan.");
        }
        return day;
    }

    private PackageForUpdate requirePackage(UUID packageId) {
        return repository.lockPackage(packageId)
                .orElseThrow(() -> new DailyWorkflowNotFoundException("Ce colis est introuvable."));
    }

    private void requirePackageInDay(OperationDay day, PackageForUpdate deliveryPackage) {
        if (deliveryPackage.requestKind() == DeliveryRequestKind.EXPRESS
                && day.serviceDate().equals(deliveryPackage.requestedDate())) {
            return;
        }
        if (day.dispatchPlanId() == null
                || deliveryPackage.dispatchPlanId() == null
                || !day.serviceDate().equals(repository.serviceDate(deliveryPackage.dispatchPlanId()))) {
            throw new DailyWorkflowNotFoundException("Ce colis n'appartient pas au plan de cette journée.");
        }
    }

    private void requireVersion(long expected, long actual, String message) {
        if (expected != actual) throw new DailyWorkflowConflictException(message);
    }

    private DailyOperationDetails currentDetails(LocalDate date) {
        OperationDay day = repository.findDay(date).orElseThrow();
        return DailyOperationDetails.of(day, repository.findPackages(day));
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
