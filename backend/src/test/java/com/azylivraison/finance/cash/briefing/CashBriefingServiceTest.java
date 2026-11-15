package com.azylivraison.finance.cash.briefing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.azylivraison.dispatch.domain.ManualDispatchDraft;
import com.azylivraison.dispatch.repository.ManualDispatchDraftRepository;
import com.azylivraison.finance.cash.CashLedgerModels;
import com.azylivraison.finance.cash.CashLedgerRepository;
import com.azylivraison.finance.cash.CashLedgerService;
import com.azylivraison.finance.service.FinanceConflictException;
import com.azylivraison.finance.service.FinanceInputException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import com.azylivraison.finance.service.PaymentProofStorage;
import com.azylivraison.planning.domain.VehicleType;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static com.azylivraison.finance.cash.briefing.CashBriefingModels.*;

/** Real SQL ledger + isolated H2, never the developer's database or routing services. */
class CashBriefingServiceTest {
    HikariDataSource database;
    JdbcTemplate jdbc;
    TransactionTemplate transactions;
    CashLedgerService ledger;
    CashBriefingService briefing;
    ManualDispatchDraftRepository drafts;
    UUID actor = UUID.randomUUID();
    UUID courier1 = UUID.randomUUID(), courier2 = UUID.randomUUID();
    UUID seller1 = UUID.randomUUID(), seller2 = UUID.randomUUID();
    LocalDate date = LocalDate.now(ZoneId.of("Indian/Antananarivo"));

    @BeforeEach void setup() {
        database = new HikariDataSource();
        database.setJdbcUrl("jdbc:h2:mem:cash_briefing_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        database.setUsername("sa"); database.setPassword("");
        Flyway.configure().dataSource(database).load().migrate();
        jdbc = new JdbcTemplate(database);
        transactions = new TransactionTemplate(new JdbcTransactionManager(database));
        ledger = new CashLedgerService(new CashLedgerRepository(jdbc), mock(PaymentProofStorage.class));
        drafts = mock(ManualDispatchDraftRepository.class);
        briefing = new CashBriefingService(jdbc, ledger, drafts);
        account(actor, "Admin", "ADMIN");
        account(seller1, "Vendeur1", "CLIENT"); account(seller2, "Vendeur2", "CLIENT");
        courier(courier1, "Livreur1"); courier(courier2, "Livreur2");
    }
    @AfterEach void teardown() { database.close(); }
    <T> T tx(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    void account(UUID id, String name, String role) {
        jdbc.update("INSERT INTO app_account (id, username, password_hash, enabled, role, display_name) VALUES (?, ?, 'dummy', TRUE, ?, ?)", id, name, role, name);
        if (role.equals("CLIENT")) {
            jdbc.update("INSERT INTO client_profile (account_id) VALUES (?)", id);
            jdbc.update("INSERT INTO client_payout_destination (id, account_id, operator, account_holder, number, priority) VALUES (?, ?, 'MVola', ?, '034 00 000 00', 1)", UUID.randomUUID(), id, name);
        }
    }
    void courier(UUID id, String name) {
        jdbc.update("""
                INSERT INTO courier_profile (id, name, vehicle_type, default_departure_time,
                    departure_location_label, departure_latitude, departure_longitude)
                VALUES (?, ?, 'MOTO', '06:00:00', 'Départ de test', -18.9, 47.5)
                """, id, name);
    }
    void adjust(String key, long amount) {
        tx(() -> ledger.adjust(new CashLedgerModels.AdjustmentRequest(UUID.randomUUID().toString(), key, amount, "Solde de test", null), actor));
    }
    UUID plan(LocalDate serviceDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dispatch_plan (id, service_date, status, strategy, mode, created_by_account_id,
                    idempotency_key, request_hash, hub_label, hub_latitude, hub_longitude,
                    input_snapshot_json, policy_snapshot_json, selected_package_count, selected_courier_count)
                VALUES (?, ?, 'READY', 'VIA_HUB', 'MANUAL', ?, ?, ?, 'Ralliement', -18.9, 47.5, '{}', '{}', 1, 2)
                """, id, serviceDate, actor, UUID.randomUUID().toString(), "0".repeat(64));
        jdbc.update("INSERT INTO delivery_operation_day (service_date, status, dispatch_plan_id) VALUES (?, 'DISPATCHED', ?)", serviceDate, id);
        var couriers = List.of(courierDraft(courier1, "Livreur1"), courierDraft(courier2, "Livreur2"));
        when(drafts.forPlan(id)).thenReturn(java.util.Optional.of(new ManualDispatchDraft(UUID.randomUUID(), serviceDate, 1, "FINALIZED",
                ManualDispatchDraft.Step.REVIEW, null, couriers, List.of(), List.of(), List.of(), List.of(), id)));
        return id;
    }
    ManualDispatchDraft.DraftCourier courierDraft(UUID id, String name) {
        return new ManualDispatchDraft.DraftCourier(id, name, null, VehicleType.MOTO, BigDecimal.TEN,
                BigDecimal.TEN, OffsetDateTime.now(), null);
    }
    Batch prepare(UUID plan) { return tx(() -> briefing.prepare(new PrepareRequest(plan, UUID.randomUUID().toString()), actor)); }
    Batch manual(UUID plan, UUID expectedBatch, ManualTransfer... transfers) {
        return tx(() -> briefing.manual(new ManualRequest(plan, UUID.randomUUID().toString(),
                ledger.revision(), expectedBatch, List.of(transfers)), actor));
    }

    @Test void reservesOneGlobalAllOrNothingProposalUsingOnlyConfirmedCourierCash() {
        adjust("COURIER:" + courier1, 6000); adjust("COURIER:" + courier2, 4000);
        adjust("AZY", 100000); adjust("SELLER:" + seller1, 10000); adjust("SELLER:" + seller2, 11000);
        Batch batch = prepare(plan(date));
        assertThat(batch.status()).isEqualTo("ACTIVE");
        assertThat(batch.transfers()).hasSize(2).allMatch(t -> t.sellerAccountId().equals(seller1));
        assertThat(batch.transfers().stream().mapToLong(Transfer::amountAriary).sum()).isEqualTo(10000);
        var snapshot = tx(ledger::getSnapshot);
        assertThat(snapshot.movements()).noneMatch(m -> m.kind().equals("TRANSFER"));
        assertThat(snapshot.accounts().stream().filter(a -> a.type().equals("COURIER")).mapToLong(CashLedgerModels.Account::reservedAriary).sum()).isEqualTo(10000);
    }

    @Test void preparingAgainOrOnAnotherPlanNeverSpendsTheSameCashTwice() {
        adjust("COURIER:" + courier1, 10000); adjust("SELLER:" + seller1, 10000);
        UUID plan = plan(date);
        PrepareRequest request = new PrepareRequest(plan, "same-preparation");
        Batch first = tx(() -> briefing.prepare(request, actor));
        assertThat(tx(() -> briefing.prepare(request, actor)).id()).isEqualTo(first.id());
        assertThat(prepare(plan).id()).isEqualTo(first.id());
        Batch next = prepare(plan(date.plusDays(1)));
        assertThat(next.transfers()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT SUM(amount_ariary) FROM cash_ledger_reservation WHERE status = 'ACTIVE'", Long.class)).isEqualTo(10000);
    }

    @Test void onlyExplicitConfirmationMovesCashAndDoubleClicksAreIdempotent() {
        adjust("COURIER:" + courier1, 30000); adjust("SELLER:" + seller1, 25000);
        Batch batch = prepare(plan(date));
        var request = new ConfirmRequest("MVola", "ref-1", "Réellement envoyé", batch.ledgerRevision());
        Batch confirmed = tx(() -> briefing.confirm(batch.transfers().getFirst().id(), request, actor, null));
        assertThat(confirmed.status()).isEqualTo("COMPLETED");
        tx(() -> briefing.confirm(batch.transfers().getFirst().id(), request, actor, null));
        var snapshot = tx(ledger::getSnapshot);
        assertThat(snapshot.accounts()).filteredOn(a -> a.key().equals("COURIER:" + courier1)).singleElement()
                .extracting(CashLedgerModels.Account::balanceAriary).isEqualTo(5000L);
        assertThat(snapshot.accounts()).filteredOn(a -> a.key().equals("SELLER:" + seller1)).singleElement()
                .extracting(CashLedgerModels.Account::balanceAriary).isEqualTo(0L);
        assertThat(snapshot.movements().stream().filter(m -> m.kind().equals("TRANSFER")).count()).isEqualTo(1);
    }

    @Test void changedCashMarksTheWholeBatchStaleAndBlocksConfirmationUntilRevalidated() {
        adjust("COURIER:" + courier1, 10000); adjust("SELLER:" + seller1, 10000);
        Batch batch = prepare(plan(date));
        adjust("AZY", 1);
        assertThat(tx(() -> briefing.list(batch.planId())).getFirst().status()).isEqualTo("STALE");
        assertThatThrownBy(() -> tx(() -> briefing.confirm(batch.transfers().getFirst().id(),
                new ConfirmRequest("MVola", null, null, batch.ledgerRevision()), actor, null))).isInstanceOf(FinanceConflictException.class);
        Batch valid = tx(() -> briefing.revalidate(batch.id()));
        assertThat(valid.status()).isEqualTo("ACTIVE");
        tx(() -> briefing.confirm(valid.transfers().getFirst().id(), new ConfirmRequest("MVola", null, null, valid.ledgerRevision()), actor, null));
    }

    @Test void insufficientCashCannotRevalidateAndDoesNotCreatePartialSuggestions() {
        adjust("COURIER:" + courier1, 10000); adjust("SELLER:" + seller1, 10000);
        Batch batch = prepare(plan(date));
        adjust("COURIER:" + courier1, -1);
        assertThatThrownBy(() -> tx(() -> briefing.revalidate(batch.id()))).isInstanceOf(FinanceConflictException.class);
        assertThat(tx(() -> briefing.list(batch.planId())).getFirst().status()).isEqualTo("STALE");
        tx(() -> briefing.release(batch.id()));
        assertThat(prepare(batch.planId()).transfers()).isEmpty();
    }

    @Test void partialConfirmationsRemainReadableAndOnlyReserveTheRemainder() {
        adjust("COURIER:" + courier1, 6000); adjust("COURIER:" + courier2, 4000); adjust("SELLER:" + seller1, 10000);
        Batch batch = prepare(plan(date));
        Batch first = tx(() -> briefing.confirm(batch.transfers().getFirst().id(),
                new ConfirmRequest("MVola", null, null, batch.ledgerRevision()), actor, null));
        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThat(first.transfers()).filteredOn(t -> t.status().equals("CONFIRMED")).hasSize(1);
        assertThat(first.transfers()).filteredOn(t -> t.status().equals("PENDING")).hasSize(1);
        adjust("AZY", 1);
        Batch valid = tx(() -> briefing.revalidate(batch.id()));
        var pending = valid.transfers().stream().filter(t -> t.status().equals("PENDING")).findFirst().orElseThrow();
        assertThat(jdbc.queryForObject("SELECT SUM(amount_ariary) FROM cash_ledger_reservation WHERE status = 'ACTIVE'", Long.class)).isEqualTo(pending.amountAriary());
        Batch completed = tx(() -> briefing.confirm(pending.id(), new ConfirmRequest("MVola", null, null, valid.ledgerRevision()), actor, null));
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.transfers()).allMatch(t -> t.status().equals("CONFIRMED"));
    }

    @Test void cancelledOrClosedPlansReleaseTheirReservationsAndCannotBePrepared() {
        adjust("COURIER:" + courier1, 10000); adjust("SELLER:" + seller1, 10000);
        Batch batch = prepare(plan(date));
        jdbc.update("UPDATE dispatch_plan SET archived_at = CURRENT_TIMESTAMP WHERE id = ?", batch.planId());
        assertThat(tx(() -> briefing.list(batch.planId())).getFirst().status()).isEqualTo("PLAN_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_ledger_reservation WHERE status = 'ACTIVE'", Long.class)).isZero();
        assertThatThrownBy(() -> prepare(batch.planId())).isInstanceOf(FinanceConflictException.class);
    }

    @Test void aChangedPayoutNumberRequiresExplicitRevalidationAndKeepsOldConfirmationsImmutable() {
        adjust("COURIER:" + courier1, 6000); adjust("COURIER:" + courier2, 4000); adjust("SELLER:" + seller1, 10000);
        Batch batch = prepare(plan(date));
        Batch first = tx(() -> briefing.confirm(batch.transfers().getFirst().id(),
                new ConfirmRequest("MVola", null, null, batch.ledgerRevision()), actor, null));
        UUID pending = first.transfers().stream().filter(t -> t.status().equals("PENDING")).findFirst().orElseThrow().id();
        jdbc.update("UPDATE client_payout_destination SET number = '034 99 999 99' WHERE account_id = ?", seller1);
        assertThat(tx(() -> briefing.list(batch.planId())).getFirst().status()).isEqualTo("STALE");
        assertThatThrownBy(() -> tx(() -> briefing.confirm(pending,
                new ConfirmRequest("MVola", null, null, first.ledgerRevision()), actor, null))).isInstanceOf(FinanceConflictException.class);
        Batch valid = tx(() -> briefing.revalidate(batch.id()));
        assertThat(valid.status()).isEqualTo("ACTIVE");
        assertThat(valid.transfers()).filteredOn(t -> t.status().equals("CONFIRMED")).singleElement()
                .extracting(Transfer::sellerPayoutNumber).isEqualTo("034 00 000 00");
        assertThat(valid.transfers()).filteredOn(t -> t.status().equals("PENDING")).singleElement()
                .extracting(Transfer::sellerPayoutNumber).isEqualTo("034 99 999 99");
    }

    @Test void closingTheDayReleasesReservationsEvenWhenOnlyFinanceIsOpened() {
        adjust("COURIER:" + courier1, 10000); adjust("SELLER:" + seller1, 10000);
        Batch batch = prepare(plan(date));
        jdbc.update("UPDATE delivery_operation_day SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP, closed_by_account_id = ? WHERE service_date = ?", actor, date);
        var snapshot = tx(ledger::getSnapshot);
        assertThat(snapshot.accounts()).filteredOn(a -> a.type().equals("COURIER"))
                .allMatch(a -> a.reservedAriary() == 0);
        assertThat(tx(() -> briefing.list(batch.planId())).getFirst().status()).isEqualTo("PLAN_INVALID");
        assertThatThrownBy(() -> prepare(batch.planId())).isInstanceOf(FinanceConflictException.class);
    }

    @Test void planningReadShowsAllSellerBalancesAndActiveCouriersWithoutGeneratingAnything() {
        adjust("SELLER:" + seller1, 12000); adjust("SELLER:" + seller2, -1000);
        adjust("COURIER:" + courier1, 7000);
        UUID plan = plan(date);
        Planning view = tx(() -> briefing.planning(plan));
        assertThat(view.batch()).isNull();
        assertThat(view.sellers()).hasSize(2);
        assertThat(view.sellers()).filteredOn(s -> s.sellerAccountId().equals(seller2)).singleElement()
                .satisfies(s -> { assertThat(s.balanceAriary()).isEqualTo(-1000); assertThat(s.availableAriary()).isZero(); });
        assertThat(view.couriers()).hasSize(2);
        assertThat(view.couriers()).filteredOn(c -> c.courierAccountId().equals(courier2)).singleElement()
                .satisfies(c -> assertThat(c.capacityAriary()).isZero());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch", Long.class)).isZero();
        assertThat(tx(ledger::getSnapshot).movements()).noneMatch(m -> m.kind().equals("TRANSFER"));
    }

    @Test void manualPlansAllowPartialSellerSettlementAndSplitAcrossCouriersWithoutMovingCash() {
        adjust("SELLER:" + seller1, 20000); adjust("COURIER:" + courier1, 7000); adjust("COURIER:" + courier2, 5000);
        UUID plan = plan(date);
        Batch batch = manual(plan, null, new ManualTransfer(seller1, courier1, 6000),
                new ManualTransfer(seller1, courier2, 3000));
        assertThat(batch.mode()).isEqualTo("MANUAL");
        assertThat(batch.transfers()).hasSize(2);
        assertThat(batch.status()).isEqualTo("ACTIVE");
        Planning view = tx(() -> briefing.planning(plan));
        assertThat(view.sellers()).filteredOn(s -> s.sellerAccountId().equals(seller1)).singleElement().satisfies(s -> {
            assertThat(s.balanceAriary()).isEqualTo(20000); assertThat(s.plannedAriary()).isEqualTo(9000);
            assertThat(s.availableAriary()).isEqualTo(20000);
        });
        assertThat(view.couriers()).filteredOn(c -> c.courierAccountId().equals(courier1)).singleElement().satisfies(c -> {
            assertThat(c.capacityAriary()).isEqualTo(7000); assertThat(c.plannedAriary()).isEqualTo(6000);
            assertThat(c.remainingAriary()).isEqualTo(1000); assertThat(c.reservedElsewhereAriary()).isZero();
        });
        assertThat(tx(ledger::getSnapshot).movements()).noneMatch(m -> m.kind().equals("TRANSFER"));
        adjust("AZY", 1);
        assertThat(tx(() -> briefing.revalidate(batch.id())).status()).isEqualTo("ACTIVE");
    }

    @Test void replacingOrClearingManualPlanPreservesConfirmedMoneyAndThePreviousHistory() {
        adjust("SELLER:" + seller1, 20000); adjust("COURIER:" + courier1, 10000); adjust("COURIER:" + courier2, 5000);
        UUID plan = plan(date);
        Batch first = manual(plan, null, new ManualTransfer(seller1, courier1, 6000), new ManualTransfer(seller1, courier2, 3000));
        Transfer real = first.transfers().getFirst();
        Batch afterPayment = tx(() -> briefing.confirm(real.id(), new ConfirmRequest("MVola", null, null, first.ledgerRevision()), actor, null));
        Batch replacement = manual(plan, afterPayment.id(), new ManualTransfer(seller1, courier1, 1000));
        assertThat(replacement.id()).isNotEqualTo(first.id());
        Batch old = tx(() -> briefing.list(plan)).stream().filter(b -> b.id().equals(first.id())).findFirst().orElseThrow();
        assertThat(old.status()).isEqualTo("RELEASED");
        assertThat(old.transfers()).filteredOn(t -> t.id().equals(real.id())).singleElement()
                .satisfies(t -> { assertThat(t.status()).isEqualTo("CONFIRMED"); assertThat(t.movementId()).isNotNull(); });
        Batch empty = manual(plan, replacement.id());
        assertThat(empty.transfers()).isEmpty();
        assertThat(tx(ledger::getSnapshot).accounts()).filteredOn(a -> a.type().equals("COURIER"))
                .allMatch(a -> a.reservedAriary() == 0);
        assertThat(tx(ledger::getSnapshot).movements().stream().filter(m -> m.kind().equals("TRANSFER")).count()).isEqualTo(1);
    }

    @Test void manualValidationRejectsExcessDebtCashInactiveCouriersAndMissingPayoutNumberAtomically() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 15000);
        UUID plan = plan(date);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, 10001)))
                .isInstanceOf(FinanceConflictException.class);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier2, 1)))
                .isInstanceOf(FinanceConflictException.class);
        UUID inactive = UUID.randomUUID(); courier(inactive, "Absent"); adjust("COURIER:" + inactive, 50000);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, inactive, 1000)))
                .isInstanceOf(FinanceConflictException.class);
        jdbc.update("DELETE FROM client_payout_destination WHERE account_id = ?", seller1);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, 1000)))
                .isInstanceOf(FinanceConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch", Long.class)).isZero();
    }

    @Test void manualPlansRespectCashAndSellerDebtAlreadyReservedByAnotherPlan() {
        adjust("SELLER:" + seller1, 10000); adjust("SELLER:" + seller2, 10000); adjust("COURIER:" + courier1, 15000);
        UUID first = plan(date), next = plan(date.plusDays(1));
        manual(first, null, new ManualTransfer(seller1, courier1, 8000));
        Planning view = tx(() -> briefing.planning(next));
        assertThat(view.sellers()).filteredOn(s -> s.sellerAccountId().equals(seller1)).singleElement()
                .satisfies(s -> assertThat(s.availableAriary()).isEqualTo(2000));
        assertThat(view.couriers()).filteredOn(c -> c.courierAccountId().equals(courier1)).singleElement().satisfies(c -> {
            assertThat(c.capacityAriary()).isEqualTo(7000); assertThat(c.reservedElsewhereAriary()).isEqualTo(8000);
        });
        assertThatThrownBy(() -> manual(next, null, new ManualTransfer(seller1, courier1, 2001)))
                .isInstanceOf(FinanceConflictException.class);
        assertThatThrownBy(() -> manual(next, null, new ManualTransfer(seller2, courier1, 7001)))
                .isInstanceOf(FinanceConflictException.class);
        Batch second = manual(next, null, new ManualTransfer(seller1, courier1, 2000));
        adjust("AZY", 1);
        assertThat(tx(() -> briefing.revalidate(second.id())).status()).isEqualTo("ACTIVE");
    }

    @Test void manualRetryIsIdempotentCombinesDuplicatePairsAndRejectsChangedBodiesOrLostUpdates() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        long revision = tx(ledger::revision);
        ManualRequest request = new ManualRequest(plan, "manual-repeat", revision, null,
                List.of(new ManualTransfer(seller1, courier1, 1000), new ManualTransfer(seller1, courier1, 2000)));
        Batch first = tx(() -> briefing.manual(request, actor));
        assertThat(first.transfers()).singleElement().extracting(Transfer::amountAriary).isEqualTo(3000L);
        assertThat(tx(() -> briefing.manual(request, actor)).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> tx(() -> briefing.manual(new ManualRequest(plan, "manual-repeat", revision, null,
                List.of(new ManualTransfer(seller1, courier1, 4000))), actor))).isInstanceOf(FinanceConflictException.class);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, 1000)))
                .isInstanceOf(FinanceConflictException.class);
        Batch replacement = manual(plan, first.id(), new ManualTransfer(seller1, courier1, 4000));
        assertThatThrownBy(() -> manual(plan, first.id(), new ManualTransfer(seller1, courier1, 1000)))
                .isInstanceOf(FinanceConflictException.class);
        adjust("AZY", 1);
        assertThatThrownBy(() -> tx(() -> briefing.manual(new ManualRequest(plan, "outdated-revision", revision, replacement.id(),
                List.of(new ManualTransfer(seller1, courier1, 1000))), actor))).isInstanceOf(FinanceConflictException.class);
        assertThat(tx(() -> briefing.planning(plan)).batch().id()).isEqualTo(replacement.id());
    }

    @Test void automaticReplacementIsAtomicAndStillSettlesSellersCompletely() {
        adjust("SELLER:" + seller1, 10000); adjust("SELLER:" + seller2, 20000); adjust("COURIER:" + courier1, 15000);
        UUID plan = plan(date);
        Batch manual = manual(plan, null, new ManualTransfer(seller2, courier1, 1000));
        PrepareRequest request = new PrepareRequest(plan, "automatic-replacement", manual.ledgerRevision(), manual.id(), true);
        Batch automatic = tx(() -> briefing.prepare(request, actor));
        assertThat(automatic.id()).isNotEqualTo(manual.id());
        assertThat(automatic.mode()).isEqualTo("AUTO");
        assertThat(automatic.transfers()).allMatch(t -> t.sellerAccountId().equals(seller1));
        assertThat(automatic.transfers().stream().mapToLong(Transfer::amountAriary).sum()).isEqualTo(10000);
        assertThat(tx(() -> briefing.prepare(request, actor)).id()).isEqualTo(automatic.id());
        assertThatThrownBy(() -> tx(() -> briefing.prepare(new PrepareRequest(plan, "bad-auto-replace", manual.ledgerRevision(), manual.id(), true), actor)))
                .isInstanceOf(FinanceConflictException.class);
        assertThat(tx(() -> briefing.planning(plan)).batch().id()).isEqualTo(automatic.id());
    }

    @Test void failedAutomaticOrManualReplacementLeavesExistingProposalAndReservationsIntact() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        Batch first = manual(plan, null, new ManualTransfer(seller1, courier1, 4000));
        CashPayoutOptimizer failing = mock(CashPayoutOptimizer.class);
        when(failing.optimize(anyList(), anyList())).thenThrow(new FinanceConflictException("Borne de calcul atteinte"));
        ReflectionTestUtils.setField(briefing, "optimizer", failing);
        assertThatThrownBy(() -> tx(() -> briefing.prepare(new PrepareRequest(plan, "failed-auto",
                first.ledgerRevision(), first.id(), true), actor))).isInstanceOf(FinanceConflictException.class);
        assertThatThrownBy(() -> manual(plan, first.id(), new ManualTransfer(seller1, courier1, 10001)))
                .isInstanceOf(FinanceConflictException.class);
        assertThat(tx(() -> briefing.planning(plan)).batch().id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT SUM(amount_ariary) FROM cash_ledger_reservation WHERE status = 'ACTIVE'", Long.class))
                .isEqualTo(4000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch", Long.class)).isEqualTo(1);
    }

    @Test void anIdempotencyKeyCannotBeSharedBetweenAutomaticAndManualPlanning() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        long revision = tx(ledger::revision);
        Batch auto = tx(() -> briefing.prepare(new PrepareRequest(plan, "mode-collision"), actor));
        assertThatThrownBy(() -> tx(() -> briefing.manual(new ManualRequest(plan, "mode-collision", revision, auto.id(),
                List.of(new ManualTransfer(seller1, courier1, 1000))), actor))).isInstanceOf(FinanceConflictException.class);
        Batch manual = manual(plan, auto.id(), new ManualTransfer(seller1, courier1, 1000));
        String key = jdbc.queryForObject("SELECT idempotency_key FROM cash_briefing_batch WHERE id = ?", String.class, manual.id());
        assertThatThrownBy(() -> tx(() -> briefing.prepare(new PrepareRequest(plan, key), actor)))
                .isInstanceOf(FinanceConflictException.class);
    }

    @Test void concurrentManualSavesCannotOverwriteEachOtherAtTheSameLedgerRevision() throws Exception {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        long revision = tx(ledger::revision);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var action = (java.util.concurrent.Callable<Batch>) () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out starting test");
                try { return tx(() -> briefing.manual(new ManualRequest(plan, UUID.randomUUID().toString(), revision, null,
                        List.of(new ManualTransfer(seller1, courier1, 3000))), actor)); }
                catch (FinanceConflictException expected) { return null; }
            };
            var first = workers.submit(action); var second = workers.submit(action);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            Batch left = first.get(10, TimeUnit.SECONDS), right = second.get(10, TimeUnit.SECONDS);
            assertThat((left == null) != (right == null)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch WHERE status = 'ACTIVE'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(amount_ariary) FROM cash_ledger_reservation WHERE status = 'ACTIVE'", Long.class)).isEqualTo(3000);
    }

    @Test void staleOverBudgetPlansCanBeCorrectedDirectlyWithoutRevalidatingTheInvalidOldAmounts() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        Batch first = manual(plan, null, new ManualTransfer(seller1, courier1, 9000));
        adjust("COURIER:" + courier1, -5000);
        Planning current = tx(() -> briefing.planning(plan));
        assertThat(current.batch().status()).isEqualTo("STALE");
        Batch corrected = tx(() -> briefing.manual(new ManualRequest(plan, "correct-stale",
                current.ledgerRevision(), first.id(), List.of(new ManualTransfer(seller1, courier1, 5000))), actor));
        assertThat(corrected.status()).isEqualTo("ACTIVE");
        assertThat(corrected.transfers()).singleElement().extracting(Transfer::amountAriary).isEqualTo(5000L);
    }

    @Test void manualAmountsRejectDecimalsUnsafeRowsDuplicateSumsAndEntityTotals() throws Exception {
        String json = "{\"sellerAccountId\":\"" + seller1 + "\",\"courierAccountId\":\"" + courier1 + "\",\"amountAriary\":1.5}";
        assertThatThrownBy(() -> JsonMapper.builder().build().readValue(json, ManualTransfer.class)).isInstanceOf(JacksonException.class);
        ManualTransfer integer = JsonMapper.builder().build().readValue(json.replace("1.5", "1500"), ManualTransfer.class);
        assertThat(integer.amountAriary()).isEqualTo(1500);
        UUID plan = plan(date);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, 0))).isInstanceOf(FinanceInputException.class);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, CashLedgerService.MAX_SAFE_ARIARY + 1)))
                .isInstanceOf(FinanceInputException.class);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, CashLedgerService.MAX_SAFE_ARIARY),
                new ManualTransfer(seller1, courier1, 1))).isInstanceOf(FinanceInputException.class);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, CashLedgerService.MAX_SAFE_ARIARY),
                new ManualTransfer(seller1, courier2, 1))).isInstanceOf(FinanceInputException.class);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, CashLedgerService.MAX_SAFE_ARIARY),
                new ManualTransfer(seller2, courier1, 1))).isInstanceOf(FinanceInputException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch", Long.class)).isZero();
    }
}
