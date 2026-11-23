package com.azylivraison.finance.cash.briefing;

import static com.azylivraison.finance.cash.briefing.CashBriefingModels.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.azylivraison.finance.cash.CashLedgerModels;
import com.azylivraison.finance.cash.CashListQuery;
import com.azylivraison.finance.service.FinanceConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises real reservations, movements and transaction rollback on an isolated database. */
class CashBriefingConsolidationTest {
    final CashBriefingServiceTest t = new CashBriefingServiceTest();
    @BeforeEach void setup() { t.setup(); }
    @AfterEach void close() { t.teardown(); }

    CashLedgerModels.Account account(UUID id) {
        return t.tx(() -> t.ledger.getSnapshot(false)).accounts().stream()
                .filter(a -> id.equals(a.entityId())).findFirst().orElseThrow();
    }
    Batch manual(UUID plan, UUID previous, List<ManualTransfer> payouts, ManualCourierTransfer... handovers) {
        return t.tx(() -> t.briefing.manual(new ManualRequest(plan, UUID.randomUUID().toString(),
                t.ledger.revision(), previous, payouts, List.of(handovers)), t.actor));
    }
    Batch confirm(CourierTransfer transfer) {
        return t.tx(() -> t.briefing.confirmCourierTransfer(transfer.id(),
                new ConfirmRequest("MVola", null, null, t.ledger.revision()), t.actor, null));
    }
    Batch pay(Transfer transfer) {
        return t.tx(() -> t.briefing.confirm(transfer.id(),
                new ConfirmRequest("MVola", null, null, t.ledger.revision()), t.actor, null));
    }
    Batch read(Batch batch) {
        return t.tx(() -> t.briefing.list(batch.planId())).stream().filter(b -> b.id().equals(batch.id())).findFirst().orElseThrow();
    }
    void seed() {
        t.adjust("COURIER:" + t.courier1, 6000);
        t.adjust("COURIER:" + t.courier2, 4000);
        t.adjust("SELLER:" + t.seller1, 10000);
    }
    Batch seededManual() {
        seed();
        return manual(t.plan(t.date), null, List.of(new ManualTransfer(t.seller1, t.courier1, 10000)),
                new ManualCourierTransfer(t.courier2, t.courier1, 4000));
    }

    @Test void automaticConsolidationCreatesOneFullPayoutAndReservesOnlyExistingCash() {
        seed();
        Batch batch = t.prepare(t.plan(t.date));
        assertThat(batch.workflowVersion()).isEqualTo(2);
        assertThat(batch.transfers()).singleElement().satisfies(p -> {
            assertThat(p.amountAriary()).isEqualTo(10000);
            assertThat(p.readyToConfirm()).isFalse();
            assertThat(p.blockedReason()).contains("transferts entrants");
        });
        assertThat(batch.courierTransfers()).singleElement().satisfies(f -> assertThat(f.readyToConfirm()).isTrue());
        assertThat(account(t.courier1).balanceAriary()).isEqualTo(6000);
        assertThat(account(t.courier1).reservedAriary()).isEqualTo(6000);
        assertThat(account(t.courier2).reservedAriary()).isEqualTo(4000);
        assertThat(t.tx(t.ledger::getSnapshot).movements()).noneMatch(m -> "TRANSFER".equals(m.kind()));
    }

    @Test void incomingCashMustBeExplicitlyConfirmedBeforeTheSingleSellerPayment() {
        Batch initial = seededManual();
        assertThatThrownBy(() -> pay(initial.transfers().getFirst())).isInstanceOf(FinanceConflictException.class);
        assertThat(account(t.seller1).balanceAriary()).isEqualTo(10000);
        Batch funded = confirm(initial.courierTransfers().getFirst());
        assertThat(account(t.courier1).balanceAriary()).isEqualTo(10000);
        assertThat(account(t.courier1).reservedAriary()).isEqualTo(10000);
        assertThat(account(t.courier2).balanceAriary()).isZero();
        assertThat(account(t.courier2).reservedAriary()).isZero();
        assertThat(funded.transfers().getFirst().readyToConfirm()).isTrue();
        Batch completed = pay(funded.transfers().getFirst());
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(account(t.seller1).balanceAriary()).isZero();
        assertThat(account(t.courier1).balanceAriary()).isZero();
        assertThat(account(t.courier1).reservedAriary()).isZero();
    }

    @Test void manualPreparationRejectsPartialAndSplitSellerPaymentsWithoutReplacingTheCurrentBatch() {
        Batch batch = seededManual();
        assertThatThrownBy(() -> manual(batch.planId(), batch.id(), List.of(new ManualTransfer(t.seller1, t.courier1, 6000))))
                .isInstanceOf(FinanceConflictException.class).hasMessageContaining("intégralement");
        assertThatThrownBy(() -> manual(batch.planId(), batch.id(), List.of(new ManualTransfer(t.seller1, t.courier1, 6000),
                new ManualTransfer(t.seller1, t.courier2, 4000))))
                .isInstanceOf(FinanceConflictException.class).hasMessageContaining("un seul");
        assertThat(read(batch).status()).isEqualTo("ACTIVE");
        assertThat(account(t.courier1).reservedAriary()).isEqualTo(6000);
        assertThat(account(t.courier2).reservedAriary()).isEqualTo(4000);
    }

    @Test void multiStepHandoversMoveActualBackingWithoutMakingExpectedCashSpendableElsewhere() {
        UUID third = UUID.randomUUID(); t.courier(third, "Livreur3");
        t.adjust("COURIER:" + t.courier1, 10000);
        t.adjust("SELLER:" + t.seller1, 10000);
        UUID plan = t.plan(t.date);
        var original = t.drafts.forPlan(plan).orElseThrow();
        var couriers = new java.util.ArrayList<>(original.couriers()); couriers.add(t.courierDraft(third, "Livreur3"));
        org.mockito.Mockito.when(t.drafts.forPlan(plan)).thenReturn(java.util.Optional.of(new com.azylivraison.dispatch.domain.ManualDispatchDraft(
                original.id(), original.serviceDate(), original.version(), original.status(), original.step(), original.hub(),
                couriers, original.packages(), original.pickups(), original.collections(), original.deliveries(), original.finalPlanId())));
        Batch batch = manual(plan, null, List.of(new ManualTransfer(t.seller1, third, 10000)),
                new ManualCourierTransfer(t.courier1, t.courier2, 10000), new ManualCourierTransfer(t.courier2, third, 10000));
        CourierTransfer first = batch.courierTransfers().stream().filter(f -> f.fromCourierAccountId().equals(t.courier1)).findFirst().orElseThrow();
        CourierTransfer second = batch.courierTransfers().stream().filter(f -> f.fromCourierAccountId().equals(t.courier2)).findFirst().orElseThrow();
        assertThat(second.readyToConfirm()).isFalse();
        assertThat(account(t.courier2).balanceAriary()).isZero();
        assertThat(account(t.courier2).reservedAriary()).isZero();
        assertThat(account(third).reservedAriary()).isZero();
        assertThatThrownBy(() -> confirm(second)).isInstanceOf(FinanceConflictException.class);
        Batch middle = confirm(first);
        assertThat(account(t.courier2).reservedAriary()).isEqualTo(10000);
        assertThat(middle.courierTransfers()).filteredOn(f -> f.id().equals(second.id())).singleElement()
                .satisfies(f -> assertThat(f.readyToConfirm()).isTrue());
        Batch funded = confirm(second);
        assertThat(account(third).reservedAriary()).isEqualTo(10000);
        assertThat(pay(funded.transfers().getFirst()).status()).isEqualTo("COMPLETED");
    }

    @Test void cyclicAndUnfundedPlansRollbackAtomically() {
        seed(); UUID plan = t.plan(t.date);
        assertThatThrownBy(() -> manual(plan, null, List.of(new ManualTransfer(t.seller1, t.courier1, 10000)),
                new ManualCourierTransfer(t.courier2, t.courier1, 4000), new ManualCourierTransfer(t.courier1, t.courier2, 4000)))
                .isInstanceOf(FinanceConflictException.class).hasMessageContaining("boucle");
        assertThatThrownBy(() -> manual(plan, null, List.of(new ManualTransfer(t.seller1, t.courier1, 10000)),
                new ManualCourierTransfer(t.courier2, t.courier1, 5000)))
                .isInstanceOf(FinanceConflictException.class);
        assertThat(t.jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch", Long.class)).isZero();
        assertThat(account(t.courier1).reservedAriary()).isZero();
    }

    @Test void anotherPlanCannotSpendTheDonorsBackingOrPromiseTheSameSeller() {
        Batch batch = seededManual();
        t.adjust("SELLER:" + t.seller2, 1);
        t.tx(() -> t.briefing.revalidate(batch.id()));
        UUID next = t.plan(t.date.plusDays(1));
        assertThatThrownBy(() -> manual(next, null, List.of(new ManualTransfer(t.seller2, t.courier2, 1))))
                .isInstanceOf(FinanceConflictException.class);
        assertThatThrownBy(() -> manual(next, null, List.of(new ManualTransfer(t.seller1, t.courier1, 10000))))
                .isInstanceOf(FinanceConflictException.class);
        assertThat(account(t.courier2).balanceAriary()).isEqualTo(4000);
        assertThat(account(t.courier2).availableAriary()).isZero();
    }

    @Test void replacingAfterARealHandoverKeepsTheMovementAndReservesTheNowRealRecipientCash() {
        Batch initial = seededManual();
        Batch funded = confirm(initial.courierTransfers().getFirst());
        UUID movementId = funded.courierTransfers().getFirst().movementId();
        Batch replacement = manual(initial.planId(), initial.id(), List.of(new ManualTransfer(t.seller1, t.courier1, 10000)));
        assertThat(replacement.courierTransfers()).isEmpty();
        assertThat(replacement.transfers().getFirst().readyToConfirm()).isTrue();
        assertThat(read(initial).status()).isEqualTo("RELEASED");
        assertThat(read(initial).courierTransfers().getFirst().movementId()).isEqualTo(movementId);
        assertThat(account(t.courier1).balanceAriary()).isEqualTo(10000);
        assertThat(account(t.courier1).reservedAriary()).isEqualTo(10000);
    }

    @Test void doubleClicksOnHandoversAndPayoutsNeverDuplicateLedgerMovements() {
        Batch initial = seededManual();
        CourierTransfer handover = initial.courierTransfers().getFirst();
        Batch funded = confirm(handover);
        confirm(handover);
        pay(funded.transfers().getFirst());
        confirm(handover); pay(funded.transfers().getFirst());
        assertThat(t.tx(t.ledger::getSnapshot).movements()).filteredOn(m -> "TRANSFER".equals(m.kind())).hasSize(2);
    }

    @Test void expiredAndClosedBatchesReleaseBatchLevelReservationsEvenOnTheCashLedgerScreen() {
        Batch batch = seededManual();
        t.jdbc.update("UPDATE cash_briefing_batch SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE id = ?", batch.id());
        assertThat(account(t.courier1).reservedAriary()).isZero();
        assertThat(account(t.courier2).reservedAriary()).isZero();
        assertThat(read(batch).status()).isEqualTo("EXPIRED");
        Batch next = manual(batch.planId(), null, List.of(new ManualTransfer(t.seller1, t.courier1, 10000)),
                new ManualCourierTransfer(t.courier2, t.courier1, 4000));
        t.jdbc.update("UPDATE delivery_operation_day SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP, closed_by_account_id = ? WHERE service_date = ?", t.actor, t.date);
        assertThat(account(t.courier1).reservedAriary()).isZero();
        assertThat(account(t.courier2).reservedAriary()).isZero();
        assertThat(read(next).status()).isEqualTo("PLAN_INVALID");
    }

    @Test void unionPaginationFiltersBothInstructionKindsBeforeCountingAndPaging() {
        Batch batch = seededManual();
        var first = t.tx(() -> t.briefing.transfers(new CashListQuery(t.date, t.date, null, 0, 1)));
        var second = t.tx(() -> t.briefing.transfers(new CashListQuery(t.date, t.date, null, 1, 1)));
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.items().getFirst().kind()).isNotEqualTo(second.items().getFirst().kind());
        var handovers = t.tx(() -> t.briefing.transfers(new CashListQuery(t.date, t.date, "livreur2", 0, 20)));
        assertThat(handovers.items()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo("COURIER_TRANSFER");
            assertThat(row.transfer()).isNull(); assertThat(row.courierTransfer()).isNotNull();
        });
        Batch funded = confirm(batch.courierTransfers().getFirst());
        t.tx(() -> t.briefing.release(funded.id()));
        var history = t.tx(() -> t.briefing.transfers(new CashListQuery(t.date, t.date, null, 0, 20)));
        assertThat(history.items()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo("COURIER_TRANSFER"); assertThat(row.batchStatus()).isEqualTo("RELEASED");
            assertThat(row.courierTransfer().status()).isEqualTo("CONFIRMED");
        });
    }

    @Test void voidingAConfirmedHandoverMakesThePlanStaleAndCannotInventTheMissingMoney() {
        Batch batch = seededManual();
        Batch funded = confirm(batch.courierTransfers().getFirst());
        UUID movement = funded.courierTransfers().getFirst().movementId();
        t.tx(() -> t.ledger.voidMovement(movement, new CashLedgerModels.VoidRequest("Erreur de confirmation", t.ledger.revision()), t.actor));
        Batch stale = read(batch);
        assertThat(stale.status()).isEqualTo("STALE");
        assertThat(stale.courierTransfers().getFirst().status()).isEqualTo("VOIDED");
        assertThatThrownBy(() -> t.tx(() -> t.briefing.revalidate(batch.id()))).isInstanceOf(FinanceConflictException.class);
        assertThatThrownBy(() -> pay(stale.transfers().getFirst())).isInstanceOf(FinanceConflictException.class);
        confirm(batch.courierTransfers().getFirst());
        assertThat(account(t.courier1).balanceAriary()).isEqualTo(6000);
    }

    @Test void negativeCourierBalancesConsumeIncomingMoneyBeforeAnyPayout() {
        t.adjust("COURIER:" + t.courier1, -5000);
        t.adjust("COURIER:" + t.courier2, 10000);
        t.adjust("SELLER:" + t.seller1, 10000);
        assertThatThrownBy(() -> manual(t.plan(t.date), null, List.of(new ManualTransfer(t.seller1, t.courier1, 10000)),
                new ManualCourierTransfer(t.courier2, t.courier1, 10000))).isInstanceOf(FinanceConflictException.class);
    }

    @Test void planningKeepsOverdraftInProjectedRemainderAndShowsHandoverOnlyPreparations() {
        t.adjust("COURIER:" + t.courier1, -5000);
        t.adjust("COURIER:" + t.courier2, 10000);
        UUID plan = t.plan(t.date);
        Batch batch = manual(plan, null, List.of(), new ManualCourierTransfer(t.courier2, t.courier1, 10000));
        assertThat(batch.message()).doesNotContain("Aucun versement");
        var view = t.tx(() -> t.briefing.planning(plan));
        assertThat(view.couriers()).filteredOn(c -> c.courierAccountId().equals(t.courier1)).singleElement().satisfies(c -> {
            assertThat(c.balanceAriary()).isEqualTo(-5000);
            assertThat(c.capacityAriary()).isZero();
            assertThat(c.incomingAriary()).isEqualTo(10000);
            assertThat(c.remainingAriary()).isEqualTo(5000);
        });
    }

    @Test void concurrentPlansCannotReserveTheSameDonorCash() throws Exception {
        seed(); t.adjust("SELLER:" + t.seller2, 10000);
        UUID firstPlan = t.plan(t.date), secondPlan = t.plan(t.date.plusDays(1));
        long revision = t.ledger.revision();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.function.BiFunction<UUID, UUID, java.util.concurrent.Callable<Batch>> action = (plan, seller) -> () -> {
                ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                try {
                    return t.tx(() -> t.briefing.manual(new ManualRequest(plan, UUID.randomUUID().toString(), revision, null,
                            List.of(new ManualTransfer(seller, t.courier1, 10000)),
                            List.of(new ManualCourierTransfer(t.courier2, t.courier1, 4000))), t.actor));
                } catch (FinanceConflictException expected) { return null; }
            };
            var first = workers.submit(action.apply(firstPlan, t.seller1));
            var second = workers.submit(action.apply(secondPlan, t.seller2));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat((first.get(10, TimeUnit.SECONDS) == null) != (second.get(10, TimeUnit.SECONDS) == null)).isTrue();
        }
        assertThat(account(t.courier1).reservedAriary()).isEqualTo(6000);
        assertThat(account(t.courier2).reservedAriary()).isEqualTo(4000);
    }

    @Test void handoverNormalizationIsIdempotentAndChangedAmountsCannotReuseTheKey() {
        seed(); UUID plan = t.plan(t.date); long revision = t.ledger.revision();
        List<ManualTransfer> payout = List.of(new ManualTransfer(t.seller1, t.courier1, 10000));
        ManualRequest request = new ManualRequest(plan, "handover-normalized", revision, null, payout,
                List.of(new ManualCourierTransfer(t.courier2, t.courier1, 1000),
                        new ManualCourierTransfer(t.courier2, t.courier1, 3000)));
        Batch first = t.tx(() -> t.briefing.manual(request, t.actor));
        assertThat(first.courierTransfers()).singleElement().satisfies(f -> assertThat(f.amountAriary()).isEqualTo(4000));
        ManualRequest same = new ManualRequest(plan, "handover-normalized", revision, null, payout,
                List.of(new ManualCourierTransfer(t.courier2, t.courier1, 4000)));
        assertThat(t.tx(() -> t.briefing.manual(same, t.actor)).id()).isEqualTo(first.id());
        confirm(first.courierTransfers().getFirst());
        assertThat(t.tx(() -> t.briefing.manual(same, t.actor)).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> t.tx(() -> t.briefing.manual(new ManualRequest(plan, "handover-normalized", revision, null,
                payout, List.of(new ManualCourierTransfer(t.courier2, t.courier1, 3999))), t.actor)))
                .isInstanceOf(FinanceConflictException.class);
        assertThat(t.tx(t.ledger::getSnapshot).movements()).filteredOn(m -> "TRANSFER".equals(m.kind())).hasSize(1);
    }
}
