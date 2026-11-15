package com.azylivraison.finance.cash;

import static com.azylivraison.finance.cash.CashLedgerModels.*;
import static com.azylivraison.finance.cash.CashLedgerService.*;
import static com.azylivraison.finance.domain.FinanceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.azylivraison.finance.service.CashClientFinanceService;
import com.azylivraison.finance.service.FinancialSettlementService;
import org.junit.jupiter.api.Test;

class CashClientFinanceCompatibilityTest {
    @Test
    void exposesUnallocatedAdvancesWithoutInventingAReportOrPayment() {
        try (var f = new CashLedgerPersistenceTest.Fixture(false)) {
            var legacy = mock(FinancialSettlementService.class);
            var reader = new CashClientFinanceService(legacy, f.service, f.jdbc);
            when(legacy.getClientFinance(f.seller)).thenReturn(history(f.seller, List.of()));
            f.run(() -> f.service.confirmTransfer(f.transfer("advance", "AZY", sellerKey(f.seller), 12_000, null), f.admin, null));
            ClientFinance before = f.call(() -> reader.finance(f.seller));
            assertThat(before.netBalanceAriary()).isEqualTo(-12_000);
            assertThat(before.amountOwedByClientAriary()).isEqualTo(12_000);
            assertThat(before.settlements()).isEmpty();
            assertThat(before.payments()).isEmpty();
            UUID report = UUID.randomUUID();
            f.run(() -> f.service.syncSellerReport(report, f.date, f.seller, 10_000, f.admin));
            when(legacy.getClientFinance(f.seller)).thenReturn(history(f.seller, List.of(settlement(f, UUID.randomUUID(), report, 10_000, 0))));
            ClientFinance after = f.call(() -> reader.finance(f.seller));
            assertThat(after.netBalanceAriary()).isEqualTo(-2_000);
            assertThat(after.totalConfirmedSignedPaymentsAriary()).isEqualTo(12_000);
            assertThat(after.settlements().getFirst().remainingAriary()).isZero();
            assertThat(after.payments()).hasSize(1);
            assertThat(after.payments().getFirst().amountAriary()).isEqualTo(10_000);
        }
    }

    @Test
    void historicBaselineFollowsNewReportRevisionButNeverEntersCourierCash() {
        try (var f = new CashLedgerPersistenceTest.Fixture(true)) {
            var legacy = mock(FinancialSettlementService.class);
            var reader = new CashClientFinanceService(legacy, f.service, f.jdbc);
            f.run(() -> f.service.reopenSellerReport(f.oldReport, f.admin));
            UUID report = UUID.randomUUID();
            f.run(() -> f.service.syncSellerReport(report, f.date, f.seller, 30_000, f.admin));
            when(legacy.getClientFinance(f.seller)).thenReturn(history(f.seller,
                    List.of(settlement(f, UUID.randomUUID(), report, 30_000, 0))));
            ClientFinance unpaid = f.call(() -> reader.finance(f.seller));
            assertThat(unpaid.netBalanceAriary()).isEqualTo(5_000);
            assertThat(unpaid.settlements().getFirst().confirmedSignedPaymentsAriary()).isEqualTo(25_000);
            assertThat(unpaid.settlements().getFirst().remainingAriary()).isEqualTo(5_000);
            assertThat(unpaid.payments()).isEmpty(); // The opening credit is not a new transfer.
            f.run(() -> f.service.confirmTransfer(f.transfer("difference", "AZY", sellerKey(f.seller), 5_000, null), f.admin, null));
            ClientFinance paid = f.call(() -> reader.finance(f.seller));
            assertThat(paid.netBalanceAriary()).isZero();
            assertThat(paid.settlements().getFirst().remainingAriary()).isZero();
            assertThat(paid.payments()).hasSize(1);
            assertThat(paid.payments().getFirst().amountAriary()).isEqualTo(5_000);
            assertThat(f.balance(courierKey(f.courier))).isZero();
            assertThat(f.balance("AZY")).isEqualTo(-5_000);
        }
    }

    private static ClientDailySettlement settlement(CashLedgerPersistenceTest.Fixture f, UUID id, UUID report, long due, long paid) {
        return new ClientDailySettlement(id, report, f.date, 1, f.seller, "Vendeur", due, paid, Instant.now());
    }

    private static ClientFinance history(UUID seller, List<ClientDailySettlement> settlements) {
        return new ClientFinance(seller, null, 0, 0, 0, 0, 0, settlements, List.of());
    }
}
