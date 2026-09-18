package com.azylivraison.dailyworkflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.dailyworkflow.domain.ClientClosureModels.ClientCandidate;
import com.azylivraison.dailyworkflow.domain.ClientClosureModels.ClientClosure;
import com.azylivraison.dailyworkflow.domain.ClientClosureModels.ClientClosureItem;
import com.azylivraison.dailyworkflow.domain.ClientClosureModels.ClientClosureStatus;
import com.azylivraison.dailyworkflow.domain.ClientClosureModels.ClientValidation;

import org.junit.jupiter.api.Test;

class ClientClosureResponseTest {

    @Test
    void reopenedClientUsesLiveTotalsWhileKeepingReopenHistory() {
        UUID clientId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Instant closedAt = Instant.parse("2026-09-17T08:00:00Z");
        Instant reopenedAt = Instant.parse("2026-09-17T09:00:00Z");
        ClientClosure previousClosure = new ClientClosure(
                LocalDate.of(2026, 9, 17), clientId, ClientClosureStatus.REOPENED, 3,
                UUID.randomUUID(), 2, 20_000, 5_000, 15_000,
                closedAt, adminId, reopenedAt, adminId, "Correction du tarif", 1, false);
        ClientValidation current = new ClientValidation(
                0, 0, 2, 0, 0, 1, 0,
                18_000, 4_000, 14_000);

        ClientClosureResponse.ClientItem response = ClientClosureResponse.ClientItem.from(
                new ClientClosureItem(
                        new ClientCandidate(clientId, "Client rouvert", "0340000000"),
                        current,
                        previousClosure));

        assertThat(response.closed()).isFalse();
        assertThat(response.readyToClose()).isTrue();
        assertThat(response.recoveredAriary()).isEqualTo(18_000);
        assertThat(response.deliveryFeesAriary()).isEqualTo(4_000);
        assertThat(response.amountToRemitAriary()).isEqualTo(14_000);
        assertThat(response.closureRevision()).isEqualTo(3);
        assertThat(response.reopenedAt()).isEqualTo(reopenedAt);
        assertThat(response.reopenReason()).isEqualTo("Correction du tarif");
    }

    @Test
    void closedClientKeepsItsFrozenTotals() {
        UUID clientId = UUID.randomUUID();
        ClientClosure closure = new ClientClosure(
                LocalDate.of(2026, 9, 17), clientId, ClientClosureStatus.CLOSED, 2,
                UUID.randomUUID(), 1, 12_000, 3_000, 9_000,
                Instant.parse("2026-09-17T08:00:00Z"), UUID.randomUUID(),
                null, null, null, 0, false);
        ClientValidation changedLiveValues = new ClientValidation(
                0, 0, 1, 0, 0, 0, 0,
                99_000, 1_000, 98_000);

        ClientClosureResponse.ClientItem response = ClientClosureResponse.ClientItem.from(
                new ClientClosureItem(
                        new ClientCandidate(clientId, "Client clos", null),
                        changedLiveValues,
                        closure));

        assertThat(response.closed()).isTrue();
        assertThat(response.recoveredAriary()).isEqualTo(12_000);
        assertThat(response.deliveryFeesAriary()).isEqualTo(3_000);
        assertThat(response.amountToRemitAriary()).isEqualTo(9_000);
    }
}
