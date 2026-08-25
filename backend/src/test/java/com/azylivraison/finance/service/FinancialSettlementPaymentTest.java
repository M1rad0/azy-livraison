package com.azylivraison.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.account.repository.AccountRepository;
import com.azylivraison.finance.domain.FinanceModels.ClientDailySettlement;
import com.azylivraison.finance.domain.FinanceModels.ClientPayment;
import com.azylivraison.finance.domain.FinanceModels.ClientPaymentDirection;
import com.azylivraison.finance.domain.FinanceModels.ClosedDay;
import com.azylivraison.finance.render.CourierStatementPdfRenderer;
import com.azylivraison.finance.render.CourierStatementXlsxRenderer;
import com.azylivraison.finance.repository.FinanceRepository;
import com.azylivraison.reporting.service.DailyReportService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FinancialSettlementPaymentTest {

    @Test
    void acceptsEitherSignedDirectionAndKeepsThePaymentAttachedToItsSettlement() {
        FinanceRepository repository = mock(FinanceRepository.class);
        PaymentProofStorage proofStorage = mock(PaymentProofStorage.class);
        FinancialSettlementService service = new FinancialSettlementService(
                repository,
                mock(DailyReportService.class),
                mock(AccountRepository.class),
                proofStorage,
                mock(CourierStatementPdfRenderer.class),
                mock(CourierStatementXlsxRenderer.class));
        UUID settlementId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        LocalDate serviceDate = LocalDate.of(2026, 8, 24);
        when(repository.lockSettlement(settlementId)).thenReturn(Optional.of(
                new ClientDailySettlement(
                        settlementId, UUID.randomUUID(), serviceDate, 1,
                        clientId, "Client", 12_000, 0, Instant.now())));
        when(repository.lockDay(serviceDate)).thenReturn(Optional.of(
                new ClosedDay(serviceDate, "CLOSED", 1)));
        when(proofStorage.store(null)).thenReturn(null);

        ClientPayment created = service.createClientPayment(
                settlementId, ClientPaymentDirection.CLIENT_TO_AZY, 2_000,
                "Espèces", null, null, "Correction", null, actorId, null);

        assertThat(created.settlementId()).isEqualTo(settlementId);
        assertThat(created.direction()).isEqualTo(ClientPaymentDirection.CLIENT_TO_AZY);
        assertThat(created.signedAmountAriary()).isEqualTo(-2_000);
        ArgumentCaptor<ClientPayment> inserted = ArgumentCaptor.forClass(ClientPayment.class);
        verify(repository).insertClientPayment(inserted.capture());
        assertThat(inserted.getValue()).isEqualTo(created);
        verify(proofStorage).store(any());
    }
}
