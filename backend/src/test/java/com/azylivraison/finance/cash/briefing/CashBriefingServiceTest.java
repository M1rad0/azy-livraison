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
import com.azylivraison.account.service.InvalidPayoutOperatorException;
import com.azylivraison.finance.cash.CashLedgerModels;
import com.azylivraison.finance.cash.CashLedgerRepository;
import com.azylivraison.finance.cash.CashLedgerService;
import com.azylivraison.finance.cash.CashListQuery;
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
    UUID payoutDestination(UUID seller, String operator, String holder, String number, int priority, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO client_payout_destination (id, account_id, operator, account_holder, number, priority, active)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, seller, operator, holder, number, priority, active);
        return id;
    }

    @Test void transferHistoryPagesIndividualTransfersAndKeepsTheTotalAcrossPages() {
        adjust("COURIER:" + courier1, 100);
        adjust("SELLER:" + seller1, 100);
        ManualTransfer[] rows = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> new ManualTransfer(seller1, courier1, 1, "034167" + String.format("%04d", i),
                        null, "Titulaire " + i, "MVola")).toArray(ManualTransfer[]::new);
        manual(plan(date), null, rows);
        var first = tx(() -> briefing.transfers(new CashListQuery(date, date, null, 0, 20)));
        var second = tx(() -> briefing.transfers(new CashListQuery(date, date, null, 1, 20)));
        assertThat(first.items()).hasSize(20);
        assertThat(second.items()).hasSize(5);
        assertThat(first.totalElements()).isEqualTo(25);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(second.items()).extracting(r -> r.transfer().id())
                .doesNotContainAnyElementsOf(first.items().stream().map(r -> r.transfer().id()).toList());
        assertThat(first.items()).allSatisfy(row -> {
            assertThat(row.serviceDate()).isEqualTo(date);
            assertThat(row.batchStatus()).isEqualTo("ACTIVE");
            assertThat(row.transfer().sellerPayoutAccountHolder()).startsWith("Titulaire");
        });
        assertThat(tx(() -> briefing.transfers(new CashListQuery(date, date, null, 2, 20))).items()).isEmpty();
        assertThat(tx(() -> ledger.getSnapshot(false)).accounts().stream()
                .filter(a -> a.entityId() != null && a.entityId().equals(courier1)).findFirst().orElseThrow().reservedAriary())
                .isEqualTo(25);
    }

    @Test void transferHistoryIncludesConfirmedHistoryButNotObsoletePendingProposals() {
        adjust("COURIER:" + courier1, 10000);
        adjust("SELLER:" + seller1, 10000);
        UUID plan = plan(date);
        Batch old = manual(plan, null, new ManualTransfer(seller1, courier1, 1000));
        Batch current = manual(plan, old.id(), new ManualTransfer(seller1, courier1, 2000),
                new ManualTransfer(seller1, courier1, 3000, "0341679343", null, "Autre titulaire", "MVola"));
        Transfer confirmed = current.transfers().getFirst();
        tx(() -> briefing.confirm(confirmed.id(), new ConfirmRequest("MVola", "REFERENCE", null, ledger.revision()), actor, null));
        tx(() -> briefing.release(current.id()));

        var page = tx(() -> briefing.transfers(new CashListQuery(date, date, "vendeur1", 0, 20)));
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(row -> {
            assertThat(row.batchId()).isEqualTo(current.id());
            assertThat(row.batchStatus()).isEqualTo("RELEASED");
            assertThat(row.transfer().id()).isEqualTo(confirmed.id());
            assertThat(row.transfer().status()).isEqualTo("CONFIRMED");
        });
        assertThat(tx(() -> briefing.transfers(new CashListQuery(date.plusDays(1), null, null, 0, 20))).items()).isEmpty();
    }

    @Test void transferHistoryExposesCompletedBatchesAndSearchesSnapshotDestinations() {
        adjust("COURIER:" + courier1, 10000);
        adjust("SELLER:" + seller1, 10000);
        Batch batch = manual(plan(date), null,
                new ManualTransfer(seller1, courier1, 1000, "+261 34 16 793 43", null, "Nom du titulaire", "MVola"));
        tx(() -> briefing.confirm(batch.transfers().getFirst().id(),
                new ConfirmRequest("MVola", null, null, ledger.revision()), actor, null));
        for (String term : List.of("0341679343", "+261341679343", "00261341679343", "nom du titulaire", "livreur1", "vendeur1")) {
            var page = tx(() -> briefing.transfers(new CashListQuery(null, date, term, 0, 20)));
            assertThat(page.items()).singleElement().satisfies(row -> assertThat(row.batchStatus()).isEqualTo("COMPLETED"));
        }
        assertThat(tx(() -> briefing.transfers(new CashListQuery(null, null, "%", 0, 20))).items()).isEmpty();
    }

    @Test void transferPageIsStaleEvenWhenChangedPayoutDestinationIsOnAnotherPage() {
        adjust("COURIER:" + courier1, 10000);
        adjust("COURIER:" + courier2, 10000);
        adjust("SELLER:" + seller1, 10000);
        adjust("SELLER:" + seller2, 10000);
        manual(plan(date), null, new ManualTransfer(seller1, courier1, 1000), new ManualTransfer(seller2, courier2, 1000));
        var otherPage = tx(() -> briefing.transfers(new CashListQuery(date, date, null, 1, 1)));
        UUID changedSeller = otherPage.items().getFirst().transfer().sellerAccountId();
        jdbc.update("UPDATE client_payout_destination SET account_holder = 'Titulaire modifié' WHERE account_id = ?", changedSeller);
        var first = tx(() -> briefing.transfers(new CashListQuery(date, date, null, 0, 1)));
        assertThat(first.items()).singleElement().satisfies(row -> {
            assertThat(row.transfer().sellerAccountId()).isNotEqualTo(changedSeller);
            assertThat(row.batchStatus()).isEqualTo("STALE");
            assertThat(row.batchMessage()).contains("revalidez");
        });
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

    @Test void aSpecificPayoutNumberIsIndependentFromTheProfileAndRemainsImmutableAfterConfirmation() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        Batch batch = manual(plan, null, new ManualTransfer(seller1, courier1, 4000,
                " 032 11 111 11 ", null, " Jean ", "  ORANGE_MONEY  "));
        assertThat(batch.status()).isEqualTo("ACTIVE");
        assertThat(batch.transfers()).singleElement().satisfies(transfer -> {
            assertThat(transfer.sellerPayoutNumber()).isEqualTo("032 11 111 11");
            assertThat(transfer.sellerPayoutNumberOverride()).isTrue();
            assertThat(transfer.sellerPayoutAccountHolder()).isEqualTo("Jean");
            assertThat(transfer.sellerPayoutOperator()).isEqualTo("Orange Money");
        });
        assertThat(jdbc.queryForObject("SELECT number FROM client_payout_destination WHERE account_id = ?",
                String.class, seller1)).isEqualTo("034 00 000 00");
        jdbc.update("UPDATE client_payout_destination SET number = '034 99 999 99' WHERE account_id = ?", seller1);
        assertThat(tx(() -> briefing.list(plan)).getFirst().status()).isEqualTo("ACTIVE");
        jdbc.update("DELETE FROM client_payout_destination WHERE account_id = ?", seller1);
        assertThat(tx(() -> briefing.list(plan)).getFirst().status()).isEqualTo("ACTIVE");
        adjust("AZY", 1);
        assertThat(tx(() -> briefing.list(plan)).getFirst().status()).isEqualTo("STALE");
        Batch revalidated = tx(() -> briefing.revalidate(batch.id()));
        assertThat(revalidated.status()).isEqualTo("ACTIVE");
        assertThat(revalidated.transfers().getFirst().sellerPayoutNumber()).isEqualTo("032 11 111 11");
        Batch confirmed = tx(() -> briefing.confirm(revalidated.transfers().getFirst().id(),
                new ConfirmRequest("MVola", null, null, revalidated.ledgerRevision()), actor, null));
        assertThat(confirmed.status()).isEqualTo("COMPLETED");
        assertThat(confirmed.transfers()).singleElement().satisfies(transfer -> {
            assertThat(transfer.status()).isEqualTo("CONFIRMED");
            assertThat(transfer.sellerPayoutNumber()).isEqualTo("032 11 111 11");
            assertThat(transfer.sellerPayoutNumberOverride()).isTrue();
        });
        tx(() -> briefing.confirm(confirmed.transfers().getFirst().id(),
                new ConfirmRequest("MVola", null, null, revalidated.ledgerRevision()), actor, null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_ledger_movement WHERE kind = 'TRANSFER'", Long.class)).isOne();
    }

    @Test void revalidationRefreshesOnlyProfileBackedNumbersEvenWithinTheSameSellerAndCourier() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        Batch batch = manual(plan, null, new ManualTransfer(seller1, courier1, 3000),
                new ManualTransfer(seller1, courier1, 2000, "032 11 111 11", null, "Jean", "Orange Money"));
        assertThat(batch.transfers()).hasSize(2);
        jdbc.update("UPDATE client_payout_destination SET number = '034 99 999 99' WHERE account_id = ?", seller1);
        assertThat(tx(() -> briefing.list(plan)).getFirst().status()).isEqualTo("STALE");
        Batch valid = tx(() -> briefing.revalidate(batch.id()));
        assertThat(valid.status()).isEqualTo("ACTIVE");
        assertThat(valid.transfers()).filteredOn(Transfer::sellerPayoutNumberOverride).singleElement()
                .extracting(Transfer::sellerPayoutNumber).isEqualTo("032 11 111 11");
        assertThat(valid.transfers()).filteredOn(t -> !t.sellerPayoutNumberOverride()).singleElement()
                .extracting(Transfer::sellerPayoutNumber).isEqualTo("034 99 999 99");
    }

    @Test void aSpecificPayoutNumberCanReplaceAMissingProfileNumberButBlankOrInvalidOverridesCannot() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        jdbc.update("DELETE FROM client_payout_destination WHERE account_id = ?", seller1);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, 1000)))
                .isInstanceOf(FinanceConflictException.class);
        for (String invalid : List.of("", " ", "0".repeat(101), "034\n1111111", "\t0341111111", "034\u00001111111")) {
            assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, 1000, invalid)))
                    .isInstanceOf(FinanceInputException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch", Long.class)).isZero();
        Batch valid = manual(plan, null, new ManualTransfer(seller1, courier1, 1000,
                "+261 34 11 111 11", null, "Jean", "Airtel-Money"));
        assertThat(valid.transfers()).singleElement().extracting(Transfer::sellerPayoutNumber)
                .isEqualTo("+261 34 11 111 11");
        jdbc.update("UPDATE cash_briefing_transfer SET seller_payout_number = '' WHERE id = ?", valid.transfers().getFirst().id());
        assertThat(tx(() -> briefing.list(plan)).getFirst().status()).isEqualTo("STALE");
        assertThatThrownBy(() -> tx(() -> briefing.revalidate(valid.id()))).isInstanceOf(FinanceConflictException.class);
        assertThatThrownBy(() -> tx(() -> briefing.confirm(valid.transfers().getFirst().id(),
                new ConfirmRequest("MVola", null, null, valid.ledgerRevision()), actor, null)))
                .isInstanceOf(FinanceConflictException.class);
    }

    @Test void specificNumbersArePartOfIdempotencyAndOnlyIdenticalNormalizedDestinationsAreCombined() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        long revision = tx(ledger::revision);
        ManualRequest request = new ManualRequest(plan, "numbers-idempotency", revision, null, List.of(
                new ManualTransfer(seller1, courier1, 1000, " 032 11 111 11 ", null, " Jean ", "orange-money"),
                new ManualTransfer(seller1, courier1, 2000, "032 11 111 11", null, "Jean", "Orange Money"),
                new ManualTransfer(seller1, courier1, 4000, "034 22 222 22", null, "Rabe", "MVola")));
        Batch first = tx(() -> briefing.manual(request, actor));
        assertThat(first.transfers()).hasSize(2);
        assertThat(first.transfers()).filteredOn(t -> t.sellerPayoutNumber().equals("032 11 111 11"))
                .singleElement().extracting(Transfer::amountAriary).isEqualTo(3000L);
        Batch replay = tx(() -> briefing.manual(new ManualRequest(plan, "numbers-idempotency", revision, null, List.of(
                new ManualTransfer(seller1, courier1, 4000, "034 22 222 22", null, "Rabe", "MVola"),
                new ManualTransfer(seller1, courier1, 3000, "032 11 111 11", null, "Jean", "Orange Money"))), actor));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> tx(() -> briefing.manual(new ManualRequest(plan, "numbers-idempotency", revision, null,
                List.of(new ManualTransfer(seller1, courier1, 7000, "034 33 333 33", null, "Jean", "MVola"))), actor)))
                .isInstanceOf(FinanceConflictException.class);
    }

    @Test void customOperatorsAreCanonicalRequiredAndPartOfNormalizationAndIdempotency() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        assertThatThrownBy(() -> manual(plan, null,
                new ManualTransfer(seller1, courier1, 1000, "0321111111", null, "Jean")))
                .isInstanceOf(InvalidPayoutOperatorException.class);
        assertThatThrownBy(() -> manual(plan, null,
                new ManualTransfer(seller1, courier1, 1000, "0321111111", null, "Jean", "Banque")))
                .isInstanceOf(InvalidPayoutOperatorException.class);

        long revision = tx(ledger::revision);
        var request = new ManualRequest(plan, "operator-idempotency", revision, null, List.of(
                new ManualTransfer(seller1, courier1, 1000, "0321111111", null, "Jean", " orange_money "),
                new ManualTransfer(seller1, courier1, 2000, "0321111111", null, "Jean", "Orange-Money"),
                new ManualTransfer(seller1, courier1, 1000, "0321111111", null, "Jean", "MVola")));
        Batch batch = tx(() -> briefing.manual(request, actor));
        assertThat(batch.transfers()).hasSize(2);
        assertThat(batch.transfers()).filteredOn(t -> "Orange Money".equals(t.sellerPayoutOperator()))
                .singleElement().extracting(Transfer::amountAriary).isEqualTo(3000L);
        assertThat(batch.transfers()).filteredOn(t -> "MVola".equals(t.sellerPayoutOperator()))
                .singleElement().extracting(Transfer::amountAriary).isEqualTo(1000L);

        Batch replay = tx(() -> briefing.manual(new ManualRequest(plan, "operator-idempotency", revision, null, List.of(
                new ManualTransfer(seller1, courier1, 1000, "0321111111", null, "Jean", "mvola"),
                new ManualTransfer(seller1, courier1, 3000, "0321111111", null, "Jean", "ORANGE_MONEY"))), actor));
        assertThat(replay.id()).isEqualTo(batch.id());
        assertThatThrownBy(() -> tx(() -> briefing.manual(new ManualRequest(plan, "operator-idempotency", revision,
                null, List.of(new ManualTransfer(seller1, courier1, 4000, "0321111111", null,
                        "Jean", "Airtel Money"))), actor))).isInstanceOf(FinanceConflictException.class);
    }

    @Test void callersOmittingNumbersRetainTheirPreOverrideIdempotencyFingerprint() throws Exception {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        long revision = tx(ledger::revision);
        Batch batch = tx(() -> briefing.manual(new ManualRequest(plan, "old-fingerprint", revision, null,
                List.of(new ManualTransfer(seller1, courier1, 1000))), actor));
        String oldTransfer = "ManualTransfer[sellerAccountId=" + seller1 + ", courierAccountId=" + courier1 + ", amountAriary=1000]";
        String oldInput = java.util.Arrays.toString(new Object[] { "MANUAL", plan, revision, null, List.of(oldTransfer) });
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(oldInput.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("SELECT request_fingerprint FROM cash_briefing_batch WHERE id = ?",
                String.class, batch.id())).isEqualTo(expected);
        assertThat(batch.transfers()).singleElement().satisfies(transfer -> {
            assertThat(transfer.sellerPayoutNumber()).isEqualTo("034 00 000 00");
            assertThat(transfer.sellerPayoutNumberOverride()).isFalse();
        });
    }

    @Test void planningListsOnlyActiveDestinationsInPriorityOrderAndAutomaticPayoutCapturesTheRealHolder() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID inactive = payoutDestination(seller1, "Airtel Money", "Personne inactive", "033 00 000 00", 2, false);
        UUID secondary = payoutDestination(seller1, "Orange Money", "Jean", "032 11 111 11", 3, true);
        UUID plan = plan(date);
        Planning view = tx(() -> briefing.planning(plan));
        var seller = view.sellers().stream().filter(s -> seller1.equals(s.sellerAccountId())).findFirst().orElseThrow();
        assertThat(seller.payoutDestinations()).hasSize(2).allMatch(d -> d.active());
        assertThat(seller.payoutDestinations()).extracting(d -> d.id()).doesNotContain(inactive).contains(secondary);
        assertThat(seller.payoutDestinations()).extracting(d -> d.priority()).containsExactly(1, 3);
        Batch automatic = prepare(plan);
        assertThat(automatic.transfers()).singleElement().satisfies(transfer -> {
            assertThat(transfer.sellerPayoutDestinationId()).isEqualTo(seller.payoutDestinations().getFirst().id());
            assertThat(transfer.sellerPayoutAccountHolder()).isEqualTo("Vendeur1");
            assertThat(transfer.sellerPayoutOperator()).isEqualTo("MVola");
            assertThat(transfer.sellerPayoutNumber()).isEqualTo("034 00 000 00");
            assertThat(transfer.sellerPayoutNumberOverride()).isFalse();
        });
    }

    @Test void newBriefingsSkipLegacyBankDestinationsAndChooseTheFirstAllowedMobileMoneyDestination() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        jdbc.update("UPDATE client_payout_destination SET operator = 'Banque' WHERE account_id = ?", seller1);
        UUID mobile = payoutDestination(seller1, "Orange Money", "Soa", "0321415216", 2, true);
        UUID plan = plan(date);
        Batch automatic = prepare(plan);
        assertThat(automatic.transfers()).singleElement().satisfies(transfer -> {
            assertThat(transfer.sellerPayoutDestinationId()).isEqualTo(mobile);
            assertThat(transfer.sellerPayoutOperator()).isEqualTo("Orange Money");
        });
        UUID legacy = jdbc.queryForObject("SELECT id FROM client_payout_destination WHERE account_id = ? AND priority = 1",
                UUID.class, seller1);
        assertThatThrownBy(() -> manual(plan, automatic.id(), new ManualTransfer(seller1, courier1, 1000, null, legacy, null)))
                .isInstanceOf(FinanceConflictException.class);
        assertThat(tx(() -> briefing.list(plan)).getFirst().id()).isEqualTo(automatic.id());

        jdbc.update("UPDATE client_payout_destination SET operator = 'Banque' WHERE id = ?", mobile);
        tx(() -> briefing.release(automatic.id()));
        Batch noMobile = prepare(plan);
        assertThat(noMobile.transfers()).isEmpty();
    }

    @Test void historicalBriefingDestinationsRemainReadableAndConfirmableWithoutRewritingTheirOperator() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        Batch batch = manual(plan, null, new ManualTransfer(seller1, courier1, 1000));
        // Simulate a proposal persisted before restricting new destinations, without altering its number/holder.
        jdbc.update("UPDATE client_payout_destination SET operator = 'Banque' WHERE account_id = ?", seller1);
        jdbc.update("UPDATE cash_briefing_transfer SET seller_payout_operator = 'Banque' WHERE batch_id = ?", batch.id());
        Batch history = tx(() -> briefing.list(plan)).getFirst();
        assertThat(history.status()).isEqualTo("ACTIVE");
        assertThat(history.transfers().getFirst().sellerPayoutOperator()).isEqualTo("Banque");
        Batch confirmed = tx(() -> briefing.confirm(history.transfers().getFirst().id(),
                new ConfirmRequest("Virement", null, null, ledger.revision()), actor, null));
        assertThat(confirmed.transfers().getFirst().status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.transfers().getFirst().sellerPayoutOperator()).isEqualTo("Banque");
    }

    @Test void selectedProfileDestinationsStayLinkedToTheirOwnNumberHolderAndOperatorNotTheFirstPriority() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID selected = payoutDestination(seller1, "Orange Money", "Jean", "032 11 111 11", 2, true);
        UUID plan = plan(date);
        Batch batch = manual(plan, null, new ManualTransfer(seller1, courier1, 4000, null, selected, null));
        assertThat(batch.status()).isEqualTo("ACTIVE");
        assertThat(batch.transfers()).singleElement().satisfies(transfer -> {
            assertThat(transfer.sellerPayoutNumberOverride()).isFalse();
            assertThat(transfer.sellerPayoutDestinationId()).isEqualTo(selected);
            assertThat(transfer.sellerPayoutAccountHolder()).isEqualTo("Jean");
        });
        jdbc.update("UPDATE client_payout_destination SET account_holder = 'Autre premier' WHERE account_id = ? AND priority = 1", seller1);
        assertThat(tx(() -> briefing.list(plan)).getFirst().status()).isEqualTo("ACTIVE");
        jdbc.update("""
                UPDATE client_payout_destination SET account_holder = 'Rabe', operator = 'MVola', number = '034 22 222 22'
                WHERE id = ?
                """, selected);
        assertThat(tx(() -> briefing.list(plan)).getFirst().status()).isEqualTo("STALE");
        Batch refreshed = tx(() -> briefing.revalidate(batch.id()));
        assertThat(refreshed.status()).isEqualTo("ACTIVE");
        assertThat(refreshed.transfers()).singleElement().satisfies(transfer -> {
            assertThat(transfer.sellerPayoutDestinationId()).isEqualTo(selected);
            assertThat(transfer.sellerPayoutAccountHolder()).isEqualTo("Rabe");
            assertThat(transfer.sellerPayoutOperator()).isEqualTo("MVola");
            assertThat(transfer.sellerPayoutNumber()).isEqualTo("034 22 222 22");
        });
        Batch confirmed = tx(() -> briefing.confirm(refreshed.transfers().getFirst().id(),
                new ConfirmRequest("MVola", null, null, refreshed.ledgerRevision()), actor, null));
        jdbc.update("UPDATE client_payout_destination SET account_holder = 'Après confirmation', number = '034 99 999 99' WHERE id = ?", selected);
        Batch history = tx(() -> briefing.list(plan)).getFirst();
        assertThat(history.status()).isEqualTo("COMPLETED");
        assertThat(history.transfers()).isEqualTo(confirmed.transfers());
    }

    @Test void removedOrInactiveSelectedDestinationsMustBeChangedInsteadOfSilentlyFallingBackToTheFirst() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID selected = payoutDestination(seller1, "Orange Money", "Jean", "032 11 111 11", 2, true);
        UUID foreign = jdbc.queryForObject("SELECT id FROM client_payout_destination WHERE account_id = ?", UUID.class, seller2);
        UUID plan = plan(date);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, 1000, null, foreign, null)))
                .isInstanceOf(FinanceConflictException.class);
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, 1000, "0321111111", selected, "Jean")))
                .isInstanceOf(FinanceInputException.class);
        Batch batch = manual(plan, null, new ManualTransfer(seller1, courier1, 4000, null, selected, null));
        jdbc.update("UPDATE client_payout_destination SET active = FALSE WHERE id = ?", selected);
        assertThat(tx(() -> briefing.list(plan)).getFirst().status()).isEqualTo("STALE");
        assertThatThrownBy(() -> tx(() -> briefing.revalidate(batch.id()))).isInstanceOf(FinanceConflictException.class);
        assertThatThrownBy(() -> manual(plan, batch.id(), new ManualTransfer(seller1, courier1, 4000, null, selected, null)))
                .isInstanceOf(FinanceConflictException.class);
        assertThat(tx(() -> briefing.list(plan)).getFirst().transfers().getFirst().sellerPayoutNumber()).isEqualTo("032 11 111 11");
    }

    @Test void customHoldersAreRequiredAndParticipateInNormalizationAndIdempotency() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        for (String holder : List.of("", " ", "X".repeat(151), "Jean\nRabe")) {
            assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(
                    seller1, courier1, 1000, "0321111111", null, holder, "Orange Money")))
                    .isInstanceOf(FinanceInputException.class);
        }
        assertThatThrownBy(() -> manual(plan, null, new ManualTransfer(seller1, courier1, 1000, "0321111111")))
                .isInstanceOf(FinanceInputException.class);
        long revision = tx(ledger::revision);
        var request = new ManualRequest(plan, "holder-idempotency", revision, null, List.of(
                new ManualTransfer(seller1, courier1, 1000, "0321111111", null, " Jean ", "orange_money"),
                new ManualTransfer(seller1, courier1, 2000, "0321111111", null, "Jean", "Orange Money"),
                new ManualTransfer(seller1, courier1, 1000, "0321111111", null, "Rabe", "Orange Money")));
        Batch batch = tx(() -> briefing.manual(request, actor));
        assertThat(batch.transfers()).hasSize(2);
        assertThat(batch.transfers()).filteredOn(t -> t.sellerPayoutAccountHolder().equals("Jean"))
                .singleElement().extracting(Transfer::amountAriary).isEqualTo(3000L);
        assertThat(tx(() -> briefing.manual(request, actor)).id()).isEqualTo(batch.id());
        assertThatThrownBy(() -> tx(() -> briefing.manual(new ManualRequest(plan, "holder-idempotency", revision, null,
                List.of(new ManualTransfer(seller1, courier1, 4000, "0321111111", null,
                        "Autre personne", "Orange Money"))), actor)))
                .isInstanceOf(FinanceConflictException.class);
    }

    @Test void legacyV26RetriesStayIdempotentWithoutInventingAnUnknownHolder() throws Exception {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        long revision = tx(ledger::revision);
        Batch batch = tx(() -> briefing.manual(new ManualRequest(plan, "v26-retry", revision, null,
                List.of(new ManualTransfer(seller1, courier1, 1000, "0321111111", null, "Jean", "MVola"))), actor));
        String oldTransfer = "ManualTransfer[sellerAccountId=" + seller1 + ", courierAccountId=" + courier1
                + ", amountAriary=1000, sellerPayoutNumber=0321111111]";
        String oldInput = java.util.Arrays.toString(new Object[] { "MANUAL", plan, revision, null, List.of(oldTransfer) });
        String oldFingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(oldInput.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("UPDATE cash_briefing_batch SET request_fingerprint = ? WHERE id = ?", oldFingerprint, batch.id());
        jdbc.update("UPDATE cash_briefing_transfer SET seller_payout_account_holder = NULL WHERE batch_id = ?", batch.id());
        Batch retry = tx(() -> briefing.manual(new ManualRequest(plan, "v26-retry", revision, null,
                List.of(new ManualTransfer(seller1, courier1, 1000, "0321111111"))), actor));
        assertThat(retry.id()).isEqualTo(batch.id());
        assertThat(retry.status()).isEqualTo("STALE");
        assertThat(retry.transfers().getFirst().sellerPayoutAccountHolder()).isNull();
        assertThatThrownBy(() -> tx(() -> briefing.revalidate(batch.id()))).isInstanceOf(FinanceConflictException.class);
        assertThatThrownBy(() -> tx(() -> briefing.confirm(retry.transfers().getFirst().id(),
                new ConfirmRequest("MVola", null, null, revision), actor, null)))
                .isInstanceOf(FinanceConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_briefing_batch", Long.class)).isOne();
    }

    @Test void legacyV27RetriesStayIdempotentWithoutInventingAnUnknownOperator() throws Exception {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        long revision = tx(ledger::revision);
        Batch batch = tx(() -> briefing.manual(new ManualRequest(plan, "v27-retry", revision, null,
                List.of(new ManualTransfer(seller1, courier1, 1000, "0321111111", null,
                        "Jean", "Orange Money"))), actor));
        String oldTransfer = "ManualTransferV27[" + seller1 + "|" + courier1
                + "|1000|10:0321111111-1:4:Jean]";
        String oldInput = java.util.Arrays.toString(new Object[] { "MANUAL", plan, revision, null, List.of(oldTransfer) });
        String oldFingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(oldInput.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("UPDATE cash_briefing_batch SET request_fingerprint = ? WHERE id = ?", oldFingerprint, batch.id());
        jdbc.update("UPDATE cash_briefing_transfer SET seller_payout_operator = NULL WHERE batch_id = ?", batch.id());

        Batch retry = tx(() -> briefing.manual(new ManualRequest(plan, "v27-retry", revision, null,
                List.of(new ManualTransfer(seller1, courier1, 1000, "0321111111", null, "Jean"))), actor));
        assertThat(retry.id()).isEqualTo(batch.id());
        assertThat(retry.transfers().getFirst().sellerPayoutOperator()).isNull();
        Batch confirmed = tx(() -> briefing.confirm(retry.transfers().getFirst().id(),
                new ConfirmRequest("Virement", null, null, revision), actor, null));
        assertThat(confirmed.transfers().getFirst().status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.transfers().getFirst().sellerPayoutOperator()).isNull();
    }

    @Test void historicalCustomOperatorsRemainReadableAndConfirmableWithoutRewriting() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        Batch batch = manual(plan, null, new ManualTransfer(seller1, courier1, 1000,
                "0321111111", null, "Jean", "Orange Money"));
        jdbc.update("UPDATE cash_briefing_transfer SET seller_payout_operator = 'Banque' WHERE batch_id = ?", batch.id());
        Batch historical = tx(() -> briefing.list(plan)).getFirst();
        assertThat(historical.status()).isEqualTo("ACTIVE");
        assertThat(historical.transfers().getFirst().sellerPayoutOperator()).isEqualTo("Banque");
        Batch confirmed = tx(() -> briefing.confirm(historical.transfers().getFirst().id(),
                new ConfirmRequest("Virement", null, null, historical.ledgerRevision()), actor, null));
        assertThat(confirmed.transfers().getFirst().sellerPayoutOperator()).isEqualTo("Banque");
    }

    @Test void customDestinationFingerprintCannotConfuseFieldDelimitersWithRealFields() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        long revision = tx(ledger::revision);
        ManualTransfer first = new ManualTransfer(seller1, courier1, 1000,
                "0321111111, sellerPayoutDestinationId=null, sellerPayoutAccountHolder=Jean", null, "Rabe", "MVola");
        ManualTransfer second = new ManualTransfer(seller1, courier1, 1000, "0321111111", null,
                "Jean, sellerPayoutDestinationId=null, sellerPayoutAccountHolder=Rabe", "MVola");
        assertThat(first.toString()).isEqualTo(second.toString()); // Record.toString alone is not an unambiguous encoding.
        tx(() -> briefing.manual(new ManualRequest(plan, "delimiters", revision, null, List.of(first)), actor));
        assertThatThrownBy(() -> tx(() -> briefing.manual(new ManualRequest(plan, "delimiters", revision, null,
                List.of(second)), actor))).isInstanceOf(FinanceConflictException.class);
    }

    @Test void legacyProfileBackedProposalsAcquireTheRealHolderOnlyAfterExplicitRevalidation() {
        adjust("SELLER:" + seller1, 10000); adjust("COURIER:" + courier1, 10000);
        UUID plan = plan(date);
        Batch batch = manual(plan, null, new ManualTransfer(seller1, courier1, 1000));
        jdbc.update("""
                UPDATE cash_briefing_transfer SET seller_payout_account_holder = NULL,
                    seller_payout_destination_id = NULL, seller_payout_operator = NULL WHERE batch_id = ?
                """, batch.id());
        Batch legacy = tx(() -> briefing.list(plan)).getFirst();
        assertThat(legacy.status()).isEqualTo("STALE");
        assertThat(legacy.transfers().getFirst().sellerPayoutAccountHolder()).isNull();
        Batch revalidated = tx(() -> briefing.revalidate(batch.id()));
        assertThat(revalidated.status()).isEqualTo("ACTIVE");
        assertThat(revalidated.transfers().getFirst().sellerPayoutAccountHolder()).isEqualTo("Vendeur1");
        assertThat(revalidated.transfers().getFirst().sellerPayoutOperator()).isEqualTo("MVola");
        assertThat(revalidated.transfers().getFirst().sellerPayoutDestinationId()).isNotNull();
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
