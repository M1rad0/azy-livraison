package com.azylivraison.finance.cash.briefing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
    @Test void internalTransportationSeedCombinesCouriersOnlyWhenTheyFullySettleTheSellerAndNeverExceedsBudgets() {
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

    @Test void fundsOnePayerInsteadOfSplittingOneSellerAcrossCouriers() {
        var main = courier(1, 60);
        var donor = courier(2, 40);
        var selected = seller(1, 100, 0);
        var couriers = List.of(main, donor);

        var result = optimizer.optimizePlan(List.of(selected, seller(2, 101, 0)), couriers);

        assertThat(result.payouts()).containsExactly(new CashPayoutOptimizer.Allocation(selected, main, 100));
        assertThat(result.courierTransfers()).containsExactly(new CashPayoutOptimizer.CashTransfer(donor, main, 40));
        assertExecutable(result, couriers);
    }

    @Test void paysDirectlyWithoutInternalTransfersWhenWholeBalancesAlreadyFit() {
        var couriers = List.of(courier(1, 60), courier(2, 40));
        var result = optimizer.optimizePlan(List.of(seller(1, 40, 0), seller(2, 60, 1)), couriers);

        assertThat(result.courierTransfers()).isEmpty();
        assertThat(result.payouts()).hasSize(2);
        assertThat(result.payouts()).allMatch(a -> a.amount() == a.seller().debt());
        assertExecutable(result, couriers);
    }

    @Test void aggregatesTheNecessaryCashForSeveralWholeSellerPayouts() {
        var couriers = List.of(courier(1, 45), courier(2, 35), courier(3, 20));
        var result = optimizer.optimizePlan(List.of(seller(1, 60, 0), seller(2, 40, 1)), couriers);

        // No pair has enough funds to pay both sellers, and the outside courier cannot pay either.
        assertThat(result.courierTransfers()).containsExactly(
                new CashPayoutOptimizer.CashTransfer(couriers.get(1), couriers.get(0), 35),
                new CashPayoutOptimizer.CashTransfer(couriers.get(2), couriers.get(0), 20));
        assertThat(result.payouts()).hasSize(2).allMatch(a -> a.courier().equals(couriers.get(0)));
        assertExecutable(result, couriers);
    }

    @Test void exactPackingAvoidsAnUnnecessaryTransferThatGreedyBestFitWouldCreate() {
        var couriers = List.of(courier(1, 10_000), courier(2, 10_000));
        var sellers = List.of(seller(1, 6_000, 0), seller(2, 5_000, 1), seller(3, 3_000, 2),
                seller(4, 2_000, 3), seller(5, 2_000, 4), seller(6, 2_000, 5));

        var result = optimizer.optimizePlan(sellers, couriers);

        assertThat(result.courierTransfers()).isEmpty();
        assertThat(result.payouts()).hasSize(6);
        assertExecutable(result, couriers);
    }

    @Test void keepsSellerCountAmountAndOldestDebtPrioritiesBeforeFundingConvenience() {
        var couriers = List.of(courier(1, 60), courier(2, 40));
        var result = optimizer.optimizePlan(List.of(seller(1, 100, 0), seller(2, 55, 5),
                seller(3, 55, 1), seller(4, 45, 0), seller(5, 40, 0)), couriers);

        // Paying 55+40 would need less money; 55+45 maximizes amount, then selects the older 55.
        assertThat(result.payouts()).extracting(a -> a.seller().id())
                .containsExactlyInAnyOrder(new UUID(0, 3), new UUID(0, 4));
        assertThat(result.courierTransfers()).hasSize(1);
        assertExecutable(result, couriers);
    }

    @Test void producesTheSameWholePayoutsAndFundingRegardlessOfInputOrder() {
        var sellers = List.of(seller(1, 60, 0), seller(2, 40, 1));
        var couriers = List.of(courier(1, 45), courier(2, 35), courier(3, 20));
        var reversedSellers = new ArrayList<>(sellers);
        var reversedCouriers = new ArrayList<>(couriers);
        Collections.reverse(reversedSellers);
        Collections.reverse(reversedCouriers);

        assertThat(optimizer.optimizePlan(reversedSellers, reversedCouriers))
                .isEqualTo(optimizer.optimizePlan(sellers, couriers));
    }

    @Test void breaksEqualPayerBalancesByCourierId() {
        var lowId = courier(1, 50);
        var highId = courier(2, 50);
        var result = optimizer.optimizePlan(List.of(seller(1, 100, 0)), List.of(highId, lowId));

        assertThat(result.payouts()).singleElement().satisfies(a -> assertThat(a.courier()).isEqualTo(lowId));
        assertThat(result.courierTransfers()).containsExactly(new CashPayoutOptimizer.CashTransfer(highId, lowId, 50));
    }

    @Test void neverInventsMoneyFromZeroOrNegativeCourierBalances() {
        var couriers = List.of(courier(1, 0), courier(2, -50), courier(3, 100));
        var result = optimizer.optimizePlan(List.of(seller(1, 100, 0)), couriers);

        assertThat(result.payouts()).singleElement().satisfies(a -> assertThat(a.courier()).isEqualTo(couriers.get(2)));
        assertThat(result.courierTransfers()).isEmpty();
        assertThat(optimizer.optimizePlan(List.of(seller(1, 1, 0)), couriers.subList(0, 2)).payouts()).isEmpty();
        assertThat(optimizer.optimizePlan(List.of(), couriers).courierTransfers()).isEmpty();
    }

    @Test void preservesLargeExactAriaryAmountsWithoutRounding() {
        var couriers = List.of(courier(1, Long.MAX_VALUE - 2), courier(2, 1));
        var result = optimizer.optimizePlan(List.of(seller(1, Long.MAX_VALUE - 1, 0)), couriers);

        assertThat(result.payouts()).singleElement().satisfies(a -> assertThat(a.amount()).isEqualTo(Long.MAX_VALUE - 1));
        assertThat(result.courierTransfers()).singleElement().satisfies(t -> assertThat(t.amount()).isEqualTo(1));
        assertExecutable(result, couriers);
    }

    @Test void rejectsOverflowAsAFinancialConflictInsteadOfPublishingACorruptPlan() {
        assertThatThrownBy(() -> optimizer.optimizePlan(List.of(seller(1, 1, 0)),
                List.of(courier(1, Long.MAX_VALUE), courier(2, 1))))
                .isInstanceOf(FinanceConflictException.class).hasMessageContaining("limite autorisee");
        assertThatThrownBy(() -> optimizer.allocate(List.of(seller(1, Long.MAX_VALUE, 0), seller(2, 1, 1)),
                List.of(courier(1, Long.MAX_VALUE))))
                .isInstanceOf(FinanceConflictException.class).hasMessageContaining("limite autorisee");
    }

    @Test void refusesAPlanWhenExactSelectionExceedsItsStateBound() {
        var bounded = new CashPayoutOptimizer(2, 100);

        assertThatThrownBy(() -> bounded.optimizePlan(List.of(seller(1, 40, 0), seller(2, 60, 1)),
                List.of(courier(1, 100))))
                .isInstanceOf(FinanceConflictException.class).hasMessageContaining("borne de complexite");
    }

    @Test void alsoBoundsSelectionWorkWhenManySellersHaveEquivalentAmounts() {
        var bounded = new CashPayoutOptimizer(3, 100);
        var sellers = IntStream.rangeClosed(1, 61).mapToObj(id -> seller(id, 1, id)).toList();

        assertThatThrownBy(() -> bounded.optimizePlan(sellers, List.of(courier(1, 1))))
                .isInstanceOf(FinanceConflictException.class).hasMessageContaining("borne de complexite");
    }

    @Test void refusesAPlanWhenExactAllocationExceedsItsBoundInsteadOfReturningAGreedyPlan() {
        var bounded = new CashPayoutOptimizer(100, 1);

        assertThatThrownBy(() -> bounded.optimizePlan(List.of(seller(1, 100, 0)),
                List.of(courier(1, 60), courier(2, 40))))
                .isInstanceOf(FinanceConflictException.class).hasMessageContaining("borne de complexite");
    }

    private void assertExecutable(CashPayoutOptimizer.Plan plan, List<CashPayoutOptimizer.Courier> couriers) {
        Map<UUID, Long> remaining = new HashMap<>();
        couriers.forEach(c -> remaining.put(c.id(), c.available()));
        assertThat(plan.courierTransfers()).allMatch(t -> t.amount() > 0 && !t.from().id().equals(t.to().id()));
        assertThat(plan.courierTransfers()).extracting(t -> List.of(t.from().id(), t.to().id())).doesNotHaveDuplicates();
        for (var transfer : plan.courierTransfers()) {
            assertThat(remaining.get(transfer.from().id())).isGreaterThanOrEqualTo(transfer.amount());
            remaining.compute(transfer.from().id(), (id, value) -> Math.subtractExact(value, transfer.amount()));
            remaining.compute(transfer.to().id(), (id, value) -> Math.addExact(value, transfer.amount()));
        }
        assertThat(plan.payouts()).extracting(a -> a.seller().id()).doesNotHaveDuplicates();
        for (var payout : plan.payouts()) {
            assertThat(payout.amount()).isEqualTo(payout.seller().debt());
            assertThat(remaining.get(payout.courier().id())).isGreaterThanOrEqualTo(payout.amount());
            remaining.compute(payout.courier().id(), (id, value) -> Math.subtractExact(value, payout.amount()));
        }
    }
}
