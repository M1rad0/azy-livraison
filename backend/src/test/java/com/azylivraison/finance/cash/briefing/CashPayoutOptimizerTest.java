package com.azylivraison.finance.cash.briefing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import com.azylivraison.finance.service.FinanceConflictException;

class CashPayoutOptimizerTest {
    private final CashPayoutOptimizer optimizer = new CashPayoutOptimizer();
    private CashPayoutOptimizer.Seller seller(int id, long debt, int days) {
        return new CashPayoutOptimizer.Seller(new UUID(0, id), "Vendeur " + id, "034" + id, debt, LocalDate.of(2026, 11, 1).plusDays(days));
    }
    private CashPayoutOptimizer.Courier courier(int id, long budget) {
        return new CashPayoutOptimizer.Courier(new UUID(1, id), "Livreur " + id, budget);
    }
    @Test void choosesMoreFullySettledSellersBeforeTheLargestDebt() {
        var result = optimizer.optimize(List.of(seller(1, 100, 0), seller(2, 45, 1), seller(3, 45, 2)), List.of(courier(1, 100)));
        assertThat(result).extracting(a -> a.seller().id()).containsExactly(new UUID(0, 2), new UUID(0, 3));
    }
    @Test void forTheSameCountMaximizesAmountThenOldestDebt() {
        var result = optimizer.optimize(List.of(seller(1, 60, 5), seller(2, 60, 1), seller(3, 40, 0)), List.of(courier(1, 100)));
        assertThat(result).extracting(a -> a.seller().id()).containsExactlyInAnyOrder(new UUID(0, 2), new UUID(0, 3));
        assertThat(result.stream().mapToLong(CashPayoutOptimizer.Allocation::amount).sum()).isEqualTo(100);
    }
    @Test void combinesCouriersOnlyWhenTheyFullySettleTheSellerAndNeverExceedsBudgets() {
        var result = optimizer.optimize(List.of(seller(1, 100, 0), seller(2, 101, 0)), List.of(courier(1, 60), courier(2, 40)));
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(a -> a.seller().id().equals(new UUID(0, 1)));
        Map<UUID, Long> totals = result.stream().collect(Collectors.groupingBy(a -> a.courier().id(), Collectors.summingLong(CashPayoutOptimizer.Allocation::amount)));
        assertThat(totals).containsEntry(new UUID(1, 1), 60L).containsEntry(new UUID(1, 2), 40L);
    }
    @Test void minimizesTheNumberOfTransfersAfterSelectingSellers() {
        var result = optimizer.optimize(List.of(seller(1, 40, 0), seller(2, 60, 1)), List.of(courier(1, 60), courier(2, 40)));
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(a -> a.amount() == a.seller().debt());
    }
    @Test void doesNotProduceIncompletePaymentsOrInventFutureCash() {
        assertThat(optimizer.optimize(List.of(seller(1, 101, 0)), List.of(courier(1, 100)))).isEmpty();
        assertThat(optimizer.optimize(List.of(seller(1, 1, 0)), List.of())).isEmpty();
        assertThat(optimizer.optimize(List.of(seller(1, 0, 0), seller(2, -10, 0)), List.of(courier(1, 100)))).isEmpty();
    }
    @Test void rejectsAnAllocationWhoseReservedCashNoLongerExists() {
        assertThatThrownBy(() -> optimizer.allocate(List.of(seller(1, 100, 0)), List.of(courier(1, 50))))
                .isInstanceOf(FinanceConflictException.class);
    }
}
