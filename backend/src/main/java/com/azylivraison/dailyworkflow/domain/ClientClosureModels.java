package com.azylivraison.dailyworkflow.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Immutable state used by the progressive client-by-client closing workflow. */
public final class ClientClosureModels {

    private ClientClosureModels() {
    }

    public enum ClientClosureStatus {
        CLOSED,
        REOPENED
    }

    public record DayState(
            LocalDate serviceDate,
            OperationDayStatus status,
            int closureRevision,
            long version) {
    }

    public record ClientCandidate(
            UUID clientAccountId,
            String displayName,
            String phone) {
    }

    public record ClientClosure(
            LocalDate serviceDate,
            UUID clientAccountId,
            ClientClosureStatus status,
            int closureRevision,
            UUID reportId,
            int packageCount,
            long recoveredAriary,
            long deliveryFeesAriary,
            long amountToRemitAriary,
            Instant closedAt,
            UUID closedByAccountId,
            Instant reopenedAt,
            UUID reopenedByAccountId,
            String reopenReason,
            long version,
            boolean courierCompensationFinalized) {

        public boolean closed() {
            return status == ClientClosureStatus.CLOSED;
        }
    }

    public record ClientValidation(
            int pendingExpressRequestCount,
            int undispatchedNormalPackageCount,
            int packageCount,
            int incompletePackageCount,
            int missingActualCourierCount,
            int incompleteReturnCount,
            int uncarriedPostponedCount,
            long recoveredAriary,
            long deliveryFeesAriary,
            long amountToRemitAriary) {

        public boolean ready() {
            return packageCount > 0
                    && pendingExpressRequestCount == 0
                    && undispatchedNormalPackageCount == 0
                    && incompletePackageCount == 0
                    && missingActualCourierCount == 0
                    && uncarriedPostponedCount == 0;
        }

        public List<String> blockers() {
            var blockers = new java.util.ArrayList<String>();
            if (packageCount == 0) blockers.add("Aucun colis traité pour ce client à cette date.");
            if (pendingExpressRequestCount > 0) {
                blockers.add(pendingExpressRequestCount + " demande(s) express attendent une confirmation ou une annulation.");
            }
            if (undispatchedNormalPackageCount > 0) {
                blockers.add(undispatchedNormalPackageCount + " colis normal(aux) ne sont pas encore dispatchés.");
            }
            if (incompletePackageCount > 0) {
                blockers.add(incompletePackageCount + " colis n'ont pas encore d'état final.");
            }
            if (missingActualCourierCount > 0) {
                blockers.add("Le livreur réel manque pour " + missingActualCourierCount + " colis facturé(s).");
            }
            if (uncarriedPostponedCount > 0) {
                blockers.add(uncarriedPostponedCount + " colis reporté(s) ne sont pas encore transmis au lendemain.");
            }
            return List.copyOf(blockers);
        }
    }

    public record ClientClosureItem(
            ClientCandidate client,
            ClientValidation validation,
            ClientClosure closure) {

        public boolean closed() {
            return closure != null && closure.closed();
        }
    }

    public record ClientClosureOverview(
            DayState day,
            List<ClientClosureItem> clients) {

        public ClientClosureOverview {
            clients = List.copyOf(clients);
        }

        public int closedClientCount() {
            return (int) clients.stream().filter(ClientClosureItem::closed).count();
        }

        public boolean allClientsClosed() {
            return !clients.isEmpty() && closedClientCount() == clients.size();
        }
    }
}
