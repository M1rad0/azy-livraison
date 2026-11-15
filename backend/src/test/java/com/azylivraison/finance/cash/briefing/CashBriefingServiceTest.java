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
import com.azylivraison.dispatch.domain.ManualDispatchDraft;
import com.azylivraison.dispatch.repository.ManualDispatchDraftRepository;
import com.azylivraison.finance.cash.CashLedgerModels;
import com.azylivraison.finance.cash.CashLedgerRepository;
import com.azylivraison.finance.cash.CashLedgerService;
import com.azylivraison.finance.service.FinanceConflictException;
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
}
