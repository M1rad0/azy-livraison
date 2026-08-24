package com.azylivraison.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.account.repository.AccountRepository;
import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import com.azylivraison.finance.domain.FinanceModels.CompensationBatch;
import com.azylivraison.finance.domain.FinanceModels.CompensationOverride;
import com.azylivraison.finance.domain.FinanceModels.CompensationSourceLine;
import com.azylivraison.finance.domain.FinanceModels.CourierEarning;
import com.azylivraison.finance.domain.FinanceModels.ClosedDay;
import com.azylivraison.finance.render.CourierStatementPdfRenderer;
import com.azylivraison.finance.render.CourierStatementXlsxRenderer;
import com.azylivraison.finance.repository.FinanceRepository;
import com.azylivraison.reporting.service.DailyReportService;
import org.junit.jupiter.api.Test;

class FinancialSettlementRoundingTest {

    private final FinanceRepository repository = mock(FinanceRepository.class);
    private final FinancialSettlementService service = new FinancialSettlementService(
            repository,
            mock(DailyReportService.class),
            mock(AccountRepository.class),
            mock(PaymentProofStorage.class),
            mock(CourierStatementPdfRenderer.class),
            mock(CourierStatementXlsxRenderer.class));

    @Test
    void roundsOneThirdToTheNearestAriary() {
        assertThat(service.suggestedAmount(3_000, 1, 3)).isEqualTo(1_000);
        assertThat(service.suggestedAmount(8_000, 1, 3)).isEqualTo(2_667);
    }

    @Test
    void appliesHalfUpWhenTheFractionFallsExactlyBetweenTwoAriary() {
        assertThat(service.suggestedAmount(1, 1, 2)).isEqualTo(1);
    }

    @Test
    void rejectsNegativeFeesAndInvalidFractions() {
        assertInvalid(-1, 1, 3);
        assertInvalid(8_000, 0, 3);
        assertInvalid(8_000, 1, 0);
        assertInvalid(8_000, 4, 3);
    }

    @Test
    void refusesToFinalizeCompensationWhileAReturnIsStillPending() {
        LocalDate serviceDate = LocalDate.of(2026, 8, 24);
        UUID packageId = UUID.randomUUID();
        configureClosedDay(serviceDate, List.of(new CompensationSourceLine(
                packageId, UUID.randomUUID(), "Hery", null,
                DeliveryPackageOutcome.RETURN_TO_SENDER, null,
                8_000, 1, 3, "Rakoto", "Analakely")));

        assertThatThrownBy(() -> service.finalizeCompensations(
                serviceDate, 2, List.of(), UUID.randomUUID()))
                .isInstanceOf(FinanceConflictException.class)
                .hasMessageContaining("retour");

        verify(repository, never()).insertCompensationBatch(any(CompensationBatch.class));
        verify(repository, never()).insertEarning(any(CourierEarning.class));
    }

    @Test
    void requiresAReasonWhenTheFinalCompensationDiffersFromTheSuggestion() {
        LocalDate serviceDate = LocalDate.of(2026, 8, 24);
        UUID packageId = UUID.randomUUID();
        configureClosedDay(serviceDate, List.of(new CompensationSourceLine(
                packageId, UUID.randomUUID(), "Hery", null,
                DeliveryPackageOutcome.DELIVERED, null,
                8_000, 1, 3, "Rakoto", "Analakely")));

        assertThatThrownBy(() -> service.finalizeCompensations(
                serviceDate, 2,
                List.of(new CompensationOverride(packageId, 3_000, null)),
                UUID.randomUUID()))
                .isInstanceOf(FinanceInputException.class)
                .hasMessageContaining("Expliquez");

        verify(repository, never()).insertCompensationBatch(any(CompensationBatch.class));
        verify(repository, never()).insertEarning(any(CourierEarning.class));
    }

    private void assertInvalid(long feeAriary, int numerator, int denominator) {
        assertThatThrownBy(() -> service.suggestedAmount(feeAriary, numerator, denominator))
                .isInstanceOf(FinanceInputException.class)
                .hasMessageContaining("fraction")
                .hasMessageContaining("invalide");
    }

    private void configureClosedDay(LocalDate serviceDate, List<CompensationSourceLine> sources) {
        when(repository.lockDay(serviceDate)).thenReturn(Optional.of(new ClosedDay(serviceDate, "CLOSED", 2)));
        when(repository.findCompensationBatch(serviceDate, 2)).thenReturn(Optional.empty());
        when(repository.compensationSourceLines(serviceDate)).thenReturn(sources);
    }
}
