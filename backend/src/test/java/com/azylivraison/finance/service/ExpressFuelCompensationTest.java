package com.azylivraison.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.account.repository.AccountRepository;
import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.delivery.domain.ExpressFuelCalculation;
import com.azylivraison.delivery.domain.ExpressFuelCalculationStatus;
import com.azylivraison.delivery.domain.ExpressFuelLine;
import com.azylivraison.delivery.service.ExpressFuelService;
import com.azylivraison.finance.domain.FinanceModels.ClosedClient;
import com.azylivraison.finance.domain.FinanceModels.ClosedDay;
import com.azylivraison.finance.domain.FinanceModels.CompensationBatch;
import com.azylivraison.finance.domain.FinanceModels.CompensationSourceLine;
import com.azylivraison.finance.domain.FinanceModels.CourierFuelEarning;
import com.azylivraison.finance.render.CourierStatementPdfRenderer;
import com.azylivraison.finance.render.CourierStatementXlsxRenderer;
import com.azylivraison.finance.repository.FinanceRepository;
import com.azylivraison.reporting.service.DailyReportService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExpressFuelCompensationTest {

    private final FinanceRepository repository = mock(FinanceRepository.class);
    private final ExpressFuelService fuelService = mock(ExpressFuelService.class);
    private final FinancialSettlementService service = new FinancialSettlementService(
            repository,
            mock(DailyReportService.class),
            mock(AccountRepository.class),
            mock(PaymentProofStorage.class),
            mock(CourierStatementPdfRenderer.class),
            mock(CourierStatementXlsxRenderer.class),
            fuelService);

    @Test
    void includesClientScopedFuelInCompensationAndSnapshotsIt() {
        LocalDate serviceDate = LocalDate.of(2026, 9, 7);
        UUID clientId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        int revision = 3;
        Instant closedAt = Instant.parse("2026-09-07T18:00:00Z");
        ExpressFuelLine fuelLine = new ExpressFuelLine(
                courierId, "Livreur snapshot", "0320000000", List.of(packageId),
                5_000L, 15_000L, 14_000, true, new BigDecimal("40"), 5_000,
                1_875, 1_750, "Distance vérifiée", true);
        ExpressFuelCalculation fuelCalculation = new ExpressFuelCalculation(
                requestId, "Client snapshot", "Retrait snapshot",
                ExpressFuelCalculationStatus.MANUAL, true, null,
                14_000, 1_875, 1_750, 2, closedAt, List.of(fuelLine));
        CompensationSourceLine commissionLine = new CompensationSourceLine(
                packageId, courierId, "Livreur snapshot", "0320000000",
                DeliveryPackageOutcome.DELIVERED, null, 9_000, 1, 3,
                "Destinataire", "Destination");

        when(repository.lockDay(serviceDate))
                .thenReturn(Optional.of(new ClosedDay(serviceDate, "OPEN", 0)));
        when(repository.lockClosedClient(serviceDate, clientId))
                .thenReturn(Optional.of(new ClosedClient(
                        serviceDate, clientId, revision, UUID.randomUUID(), closedAt)));
        when(repository.findClientCompensationBatch(serviceDate, revision, clientId))
                .thenReturn(Optional.empty());
        when(repository.compensationSourceLines(serviceDate, clientId))
                .thenReturn(List.of(commissionLine));
        when(fuelService.forDateAndClient(serviceDate, clientId))
                .thenReturn(List.of(fuelCalculation));

        var result = service.finalizeCompensations(
                serviceDate, clientId, revision, List.of(), actorId);

        assertThat(result.totalFinalCommissionAriary()).isEqualTo(3_000);
        assertThat(result.totalFinalFuelAriary()).isEqualTo(1_750);
        assertThat(result.totalFinalAriary()).isEqualTo(4_750);
        ArgumentCaptor<CompensationBatch> batch = ArgumentCaptor.forClass(CompensationBatch.class);
        verify(repository).insertCompensationBatch(batch.capture());
        assertThat(batch.getValue().clientAccountId()).isEqualTo(clientId);
        assertThat(batch.getValue().totalFinalCommissionAriary()).isEqualTo(3_000);
        assertThat(batch.getValue().totalFinalFuelAriary()).isEqualTo(1_750);
        assertThat(batch.getValue().totalFinalAriary()).isEqualTo(4_750);

        ArgumentCaptor<CourierFuelEarning> earning = ArgumentCaptor.forClass(CourierFuelEarning.class);
        verify(repository).insertFuelEarning(earning.capture());
        assertThat(earning.getValue()).satisfies(snapshot -> {
            assertThat(snapshot.requestId()).isEqualTo(requestId);
            assertThat(snapshot.courierId()).isEqualTo(courierId);
            assertThat(snapshot.courierName()).isEqualTo("Livreur snapshot");
            assertThat(snapshot.courierContact()).isEqualTo("0320000000");
            assertThat(snapshot.packageIds()).containsExactly(packageId);
            assertThat(snapshot.returnToPickup()).isTrue();
            assertThat(snapshot.repositionDistanceMeters()).isEqualTo(5_000);
            assertThat(snapshot.automaticDistanceMeters()).isEqualTo(15_000);
            assertThat(snapshot.finalDistanceMeters()).isEqualTo(14_000);
            assertThat(snapshot.fuelEfficiencyKmPerLiter()).isEqualByComparingTo("40");
            assertThat(snapshot.fuelPricePerLiterAriary()).isEqualTo(5_000);
            assertThat(snapshot.suggestedAmountAriary()).isEqualTo(1_875);
            assertThat(snapshot.finalAmountAriary()).isEqualTo(1_750);
            assertThat(snapshot.overrideReason()).isEqualTo("Distance vérifiée");
            assertThat(snapshot.manual()).isTrue();
        });
    }
}
