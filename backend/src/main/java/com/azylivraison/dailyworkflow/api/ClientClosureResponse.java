package com.azylivraison.dailyworkflow.api;

import static com.azylivraison.dailyworkflow.domain.ClientClosureModels.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.dailyworkflow.domain.OperationDayStatus;

public record ClientClosureResponse(
        LocalDate serviceDate,
        OperationDayStatus dayStatus,
        long dayVersion,
        int currentClosureRevision,
        int clientCount,
        int closedClientCount,
        boolean allClientsClosed,
        List<ClientItem> clients) {

    public ClientClosureResponse {
        clients = List.copyOf(clients);
    }

    static ClientClosureResponse from(ClientClosureOverview overview) {
        return new ClientClosureResponse(
                overview.day().serviceDate(), overview.day().status(), overview.day().version(),
                overview.day().closureRevision(), overview.clients().size(), overview.closedClientCount(),
                overview.allClientsClosed(), overview.clients().stream().map(ClientItem::from).toList());
    }

    public record ClientItem(
            UUID clientAccountId,
            String clientDisplayName,
            String clientPhone,
            boolean readyToClose,
            List<String> blockers,
            int packageCount,
            long recoveredAriary,
            long deliveryFeesAriary,
            long amountToRemitAriary,
            boolean closed,
            Integer closureRevision,
            UUID reportId,
            Instant closedAt,
            UUID closedByAccountId,
            Instant reopenedAt,
            UUID reopenedByAccountId,
            String reopenReason,
            boolean courierCompensationFinalized,
            Long version) {

        public ClientItem {
            blockers = List.copyOf(blockers);
        }

        static ClientItem from(ClientClosureItem item) {
            ClientClosure closure = item.closure();
            ClientValidation validation = item.validation();
            boolean closed = item.closed();
            return new ClientItem(
                    item.client().clientAccountId(), item.client().displayName(), item.client().phone(),
                    !closed && validation.ready(), closed ? List.of() : validation.blockers(),
                    closed ? closure.packageCount() : validation.packageCount(),
                    closed ? closure.recoveredAriary() : validation.recoveredAriary(),
                    closed ? closure.deliveryFeesAriary() : validation.deliveryFeesAriary(),
                    closed ? closure.amountToRemitAriary() : validation.amountToRemitAriary(),
                    closed, closure == null ? null : closure.closureRevision(),
                    closure == null ? null : closure.reportId(),
                    closure == null ? null : closure.closedAt(),
                    closure == null ? null : closure.closedByAccountId(),
                    closure == null ? null : closure.reopenedAt(),
                    closure == null ? null : closure.reopenedByAccountId(),
                    closure == null ? null : closure.reopenReason(),
                    closure != null && closure.courierCompensationFinalized(),
                    closure == null ? null : closure.version());
        }
    }
}
