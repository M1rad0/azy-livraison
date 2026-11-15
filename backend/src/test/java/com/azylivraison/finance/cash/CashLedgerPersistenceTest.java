package com.azylivraison.finance.cash;

import static com.azylivraison.finance.cash.CashLedgerModels.*;
import static com.azylivraison.finance.cash.CashLedgerService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.azylivraison.finance.service.FinanceConflictException;
import com.azylivraison.finance.service.FinanceInputException;
import com.azylivraison.finance.service.PaymentProofStorage;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import com.azylivraison.finance.domain.FinanceModels.ProofMetadata;

class CashLedgerPersistenceTest {
    @Test
    void upgradeStartsAtZeroAndOnlyRecordsCorrectionsToHistoricalCollections() {
        try (Fixture f = new Fixture(true)) {
            assertThat(f.snapshot().accounts()).allSatisfy(a -> assertThat(a.balanceAriary()).isZero());
            assertThat(f.snapshot().movements()).isEmpty();
            f.run(() -> f.service.syncCollection(f.parcel, f.courier, 30_000, f.admin));
            f.run(() -> f.service.syncSellerReport(f.oldReport, f.date, f.seller, 25_000, f.admin));
            assertThat(f.snapshot().revision()).isZero();
            f.run(() -> f.service.syncCollection(f.parcel, f.courier, 35_000, f.admin));
            assertThat(f.balance(courierKey(f.courier))).isEqualTo(5_000);
            f.run(() -> f.service.syncCollection(f.parcel, f.otherCourier, 35_000, f.admin));
            assertThat(f.balance(courierKey(f.courier))).isEqualTo(-30_000);
            assertThat(f.balance(courierKey(f.otherCourier))).isEqualTo(35_000);
        }
    }

    @Test
    void actualReceiptsAreIdempotentAndFollowActualCourierCorrections() {
        try (Fixture f = new Fixture(false)) {
            f.run(() -> f.service.syncCollection(f.parcel, f.courier, 30_000, f.admin));
            f.run(() -> f.service.syncCollection(f.parcel, f.courier, 30_000, f.admin));
            assertThat(f.snapshot().movements()).hasSize(1);
            f.run(() -> f.service.syncCollection(f.parcel, f.otherCourier, 20_000, f.admin));
            assertThat(f.balance(courierKey(f.courier))).isZero();
            assertThat(f.balance(courierKey(f.otherCourier))).isEqualTo(20_000);
            f.run(() -> f.service.syncCollection(f.parcel, null, 0, f.admin));
            assertThat(f.balance(courierKey(f.otherCourier))).isZero();
            assertThatThrownBy(() -> f.run(() -> f.service.syncCollection(f.parcel, null, 1, f.admin)))
                    .isInstanceOf(FinanceInputException.class);
        }
    }

    @Test
    void paymentSettlesOldestReportsAndVoidRestoresMoneyAndAllocations() {
        try (Fixture f = new Fixture(false)) {
            UUID oldest = UUID.randomUUID();
            UUID newest = UUID.randomUUID();
            f.run(() -> f.service.syncCollection(f.parcel, f.courier, 30_000, f.admin));
            f.run(() -> f.service.syncSellerReport(newest, f.date.plusDays(1), f.seller, 15_000, f.admin));
            f.run(() -> f.service.syncSellerReport(oldest, f.date, f.seller, 10_000, f.admin));
            Movement payment = f.call(() -> f.service.confirmTransfer(f.transfer("payment", courierKey(f.courier),
                    sellerKey(f.seller), 25_000, null), f.admin, null));
            assertThat(f.balance(courierKey(f.courier))).isEqualTo(5_000);
            assertThat(f.balance(sellerKey(f.seller))).isZero();
            assertThat(f.snapshot().obligations()).allSatisfy(o -> assertThat(o.remainingAriary()).isZero());
            assertThatThrownBy(() -> f.run(() -> f.service.reopenSellerReport(oldest, f.admin)))
                    .isInstanceOf(FinanceConflictException.class);
            f.run(() -> f.service.voidMovement(payment.id(), new VoidRequest("Erreur", null), f.admin));
            f.run(() -> f.service.voidMovement(payment.id(), new VoidRequest("Erreur", 0L), f.admin));
            assertThat(f.balance(courierKey(f.courier))).isEqualTo(30_000);
            assertThat(f.balance(sellerKey(f.seller))).isEqualTo(25_000);
            assertThat(f.snapshot().obligations()).allSatisfy(o -> assertThat(o.paidAriary()).isZero());
            assertThat(f.snapshot().movements().stream().filter(m -> m.kind().equals("REVERSAL"))).hasSize(1);
            f.run(() -> f.service.reopenSellerReport(oldest, f.admin));
            assertThat(f.balance(sellerKey(f.seller))).isEqualTo(15_000);
        }
    }

    @Test
    void carriesHistoricalSellerBaselineAcrossReopeningAndNewReportIds() {
        try (Fixture f = new Fixture(true)) {
            f.run(() -> f.service.reopenSellerReport(f.oldReport, f.admin));
            UUID newReport = UUID.randomUUID();
            f.run(() -> f.service.syncSellerReport(newReport, f.date, f.seller, 30_000, f.admin));
            assertThat(f.balance(sellerKey(f.seller))).isEqualTo(5_000);
            f.run(() -> f.service.reopenSellerReport(newReport, f.admin));
            assertThat(f.balance(sellerKey(f.seller))).isZero();
            f.run(() -> f.service.syncSellerReport(UUID.randomUUID(), f.date, f.seller, 35_000, f.admin));
            assertThat(f.balance(sellerKey(f.seller))).isEqualTo(10_000);
        }
    }

    @Test
    void negativeSellerDebtsAndAllEntityTransfersKeepTheirSignedMeaning() {
        try (Fixture f = new Fixture(false)) {
            f.run(() -> f.service.syncSellerReport(UUID.randomUUID(), f.date, f.seller, -5_000, f.admin));
            f.run(() -> f.service.confirmTransfer(f.transfer("seller-pays", sellerKey(f.seller), "AZY", 5_000, null), f.admin, null));
            assertThat(f.balance("AZY")).isEqualTo(5_000);
            assertThat(f.balance(sellerKey(f.seller))).isZero();
            assertThat(f.snapshot().obligations().getFirst().paidAriary()).isEqualTo(-5_000);
            f.run(() -> f.service.confirmTransfer(f.transfer("advance", "AZY", courierKey(f.courier), 10_000, null), f.admin, null));
            f.run(() -> f.service.confirmTransfer(f.transfer("peer", courierKey(f.courier), courierKey(f.otherCourier), 2_000, null), f.admin, null));
            assertThat(f.balance("AZY")).isEqualTo(-5_000);
            assertThat(f.balance(courierKey(f.courier))).isEqualTo(8_000);
            assertThat(f.balance(courierKey(f.otherCourier))).isEqualTo(2_000);
        }
    }

    @Test
    void reconciliationCanZeroSellerBalancesAndIsReversibleAndAudited() {
        try (Fixture f = new Fixture(false)) {
            f.run(() -> f.service.syncSellerReport(UUID.randomUUID(), f.date, f.seller, 25_000, f.admin));
            Movement adjustment = f.call(() -> f.service.adjust(new AdjustmentRequest("reset", sellerKey(f.seller),
                    -25_000, "Régularisation avant bascule", null), f.admin));
            assertThat(f.balance(sellerKey(f.seller))).isZero();
            assertThat(f.snapshot().obligations().getFirst().remainingAriary()).isZero();
            f.run(() -> f.service.voidMovement(adjustment.id(), new VoidRequest("Correction", null), f.admin));
            assertThat(f.balance(sellerKey(f.seller))).isEqualTo(25_000);
            assertThat(f.snapshot().obligations().getFirst().paidAriary()).isZero();
            assertThatThrownBy(() -> f.run(() -> f.service.adjust(new AdjustmentRequest("bad", "AZY", 5, " ", null), f.admin)))
                    .isInstanceOf(FinanceInputException.class);
        }
    }

    @Test
    void retriesCannotChangePayloadAndUnsafeTotalsAreRejectedWithoutPosting() {
        try (Fixture f = new Fixture(false)) {
            TransferRequest request = f.transfer("once", "AZY", courierKey(f.courier), 100, 0L);
            Movement first = f.call(() -> f.service.confirmTransfer(request, f.admin, null));
            assertThat(f.call(() -> f.service.confirmTransfer(request, f.admin, null)).id()).isEqualTo(first.id());
            assertThatThrownBy(() -> f.run(() -> f.service.confirmTransfer(f.transfer("once", "AZY",
                    courierKey(f.courier), 101, null), f.admin, null))).isInstanceOf(FinanceConflictException.class);
            assertThatThrownBy(() -> f.run(() -> f.service.confirmTransfer(f.transfer("stale", "AZY",
                    courierKey(f.courier), 1, 0L), f.admin, null))).isInstanceOf(FinanceConflictException.class);
            f.run(() -> f.service.adjust(new AdjustmentRequest("big", courierKey(f.courier), MAX_SAFE_ARIARY - 100,
                    "Limite", null), f.admin));
            assertThatThrownBy(() -> f.run(() -> f.service.confirmTransfer(f.transfer("overflow", "AZY",
                    courierKey(f.courier), 1, null), f.admin, null))).isInstanceOf(FinanceInputException.class);
            assertThatThrownBy(() -> f.run(() -> f.service.confirmTransfer(f.transfer("large", "AZY",
                    courierKey(f.otherCourier), Long.MAX_VALUE, null), f.admin, null))).isInstanceOf(FinanceInputException.class);
            assertThat(f.snapshot().movements()).hasSize(2);
        }
    }

    @Test
    void simultaneousRetriesUseOnePostingAndConcurrentStaleRevisionCannotSpendTwice() throws Exception {
        try (Fixture f = new Fixture(false); var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch gate = new CountDownLatch(1);
            TransferRequest request = f.transfer("race", "AZY", courierKey(f.courier), 100, null);
            var a = executor.submit(() -> { gate.await(); return f.call(() -> f.service.confirmTransfer(request, f.admin, null)); });
            var b = executor.submit(() -> { gate.await(); return f.call(() -> f.service.confirmTransfer(request, f.admin, null)); });
            gate.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS).id()).isEqualTo(b.get(10, TimeUnit.SECONDS).id());
            assertThat(f.balance(courierKey(f.courier))).isEqualTo(100);
            long revision = f.snapshot().revision();
            CountDownLatch secondGate = new CountDownLatch(1);
            var firstWrite = executor.submit(() -> f.race(secondGate, "first", revision));
            var secondWrite = executor.submit(() -> f.race(secondGate, "second", revision));
            secondGate.countDown();
            assertThat(List.of(firstWrite.get(10, TimeUnit.SECONDS), secondWrite.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(f.snapshot().movements()).hasSize(2);
        }
    }
    @Test
    void partialPaymentsPreferOldestReportAndAdvancesAreAllocatedWhenReportsClose() {
        try (Fixture f = new Fixture(false)) {
            Movement advance = f.call(() -> f.service.confirmTransfer(f.transfer("advance", "AZY", sellerKey(f.seller),
                    12_000, null), f.admin, null));
            assertThat(f.balance(sellerKey(f.seller))).isEqualTo(-12_000);
            UUID oldest = UUID.randomUUID();
            UUID newest = UUID.randomUUID();
            f.run(() -> f.service.syncSellerReport(oldest, f.date, f.seller, 10_000, f.admin));
            f.run(() -> f.service.syncSellerReport(newest, f.date.plusDays(1), f.seller, 15_000, f.admin));
            assertThat(f.snapshot().obligations().stream().filter(o -> o.reportId().equals(oldest)).findFirst().orElseThrow().paidAriary())
                    .isEqualTo(10_000);
            assertThat(f.snapshot().obligations().stream().filter(o -> o.reportId().equals(newest)).findFirst().orElseThrow().paidAriary())
                    .isEqualTo(2_000);
            assertThat(f.balance(sellerKey(f.seller))).isEqualTo(13_000);
            f.run(() -> f.service.voidMovement(advance.id(), new VoidRequest("Annulation avance", null), f.admin));
            assertThat(f.snapshot().obligations()).allSatisfy(o -> assertThat(o.paidAriary()).isZero());
            assertThat(f.balance(sellerKey(f.seller))).isEqualTo(25_000);
        }
    }

    @Test
    void ordinaryMutationReleasesReservationsButBriefingConfirmationPreservesOthers() {
        try (Fixture f = new Fixture(false)) {
            f.run(() -> f.service.syncCollection(f.parcel, f.courier, 100, f.admin));
            f.jdbc.update("INSERT INTO cash_ledger_reservation (id,proposal_id,account_key,amount_ariary,status) VALUES (?,?,?,80,'ACTIVE')",
                    UUID.randomUUID(), UUID.randomUUID(), courierKey(f.courier));
            assertThat(f.snapshot().accounts().stream().filter(a -> a.key().equals(courierKey(f.courier))).findFirst().orElseThrow().availableAriary())
                    .isEqualTo(20);
            f.run(() -> f.service.adjust(new AdjustmentRequest("change", "AZY", 1, "Correction", null), f.admin));
            assertThat(f.snapshot().accounts().stream().filter(a -> a.key().equals(courierKey(f.courier))).findFirst().orElseThrow().reservedAriary())
                    .isZero();
            f.jdbc.update("INSERT INTO cash_ledger_reservation (id,proposal_id,account_key,amount_ariary,status) VALUES (?,?,?,50,'ACTIVE')",
                    UUID.randomUUID(), UUID.randomUUID(), courierKey(f.courier));
            f.run(() -> {
                f.service.lock();
                f.service.confirmTransferInternal(f.transfer("briefing", courierKey(f.courier), "AZY", 5, null), f.admin, null);
            });
            assertThat(f.snapshot().accounts().stream().filter(a -> a.key().equals(courierKey(f.courier))).findFirst().orElseThrow().availableAriary())
                    .isEqualTo(45);
        }
    }

    @Test
    void rollbackRemovesStoredProofAndAutomaticPostingsCannotBeVoidedDirectly() {
        try (Fixture f = new Fixture(false)) {
            var proof = new MockMultipartFile("proof", "recu.pdf", "application/pdf", "%PDF-1.4 proof".getBytes());
            ProofMetadata metadata = new ProofMetadata("proof-key.pdf", "recu.pdf", "application/pdf", proof.getSize(), "a".repeat(64));
            when(f.proofStorage.store(proof)).thenReturn(metadata);
            assertThatThrownBy(() -> f.run(() -> f.service.confirmTransfer(f.transfer("bad-actor", "AZY", courierKey(f.courier),
                    100, null), UUID.randomUUID(), proof))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            verify(f.proofStorage).deleteQuietly("proof-key.pdf");
            assertThat(f.snapshot().movements()).isEmpty();
            f.run(() -> f.service.syncCollection(f.parcel, f.courier, 100, f.admin));
            UUID automatic = f.snapshot().movements().getFirst().id();
            assertThatThrownBy(() -> f.run(() -> f.service.voidMovement(automatic, new VoidRequest("Interdit", null), f.admin)))
                    .isInstanceOf(FinanceInputException.class);
            assertThat(f.balance(courierKey(f.courier))).isEqualTo(100);
        }
    }

    static final class Fixture implements AutoCloseable {
        final UUID admin = UUID.randomUUID();
        final UUID seller = UUID.randomUUID();
        final UUID courier = UUID.randomUUID();
        final UUID otherCourier = UUID.randomUUID();
        final UUID parcel = UUID.randomUUID();
        final UUID oldReport = UUID.randomUUID();
        final LocalDate date = LocalDate.of(2026, 11, 15);
        final HikariDataSource source = new HikariDataSource();
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final CashLedgerService service;
        final PaymentProofStorage proofStorage = mock(PaymentProofStorage.class);

        Fixture(boolean historical) {
            source.setJdbcUrl("jdbc:h2:mem:cash_" + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            source.setUsername("sa");
            source.setPassword("");
            Flyway.configure().dataSource(source).target("22").load().migrate();
            jdbc = new JdbcTemplate(source);
            account(admin, "ADMIN");
            account(seller, "CLIENT"); // A legacy account need not yet have a client_profile.
            jdbc.update("INSERT INTO courier_profile (id,name,vehicle_type,default_departure_time,departure_latitude,departure_longitude) VALUES (?, 'Hery', 'MOTO', '06:00:00', -18.9,47.5)", courier);
            jdbc.update("INSERT INTO courier_profile (id,name,vehicle_type,default_departure_time,departure_latitude,departure_longitude) VALUES (?, 'Jean', 'MOTO', '06:00:00', -18.9,47.5)", otherCourier);
            UUID request = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO delivery_request (id,owner_account_id,requested_date,pickup_label,pickup_latitude,
                        pickup_longitude,capacity_limit_snapshot,capacity_current_snapshot,capacity_requested_snapshot,
                        capacity_projected_snapshot) VALUES (?, ?, ?, 'Retrait', -18.9,47.5,100,0,1,1)
                    """, request, seller, date);
            jdbc.update("""
                    INSERT INTO delivery_package (id,delivery_request_id,sequence_number,recipient_name,faritra,
                        destination_label,destination_latitude,destination_longitude,contact,amount_to_collect_ariary,
                        mobile_money_paid,effective_weight_kg,weight_source,priority_at)
                    VALUES (?, ?, 1, 'Destinataire', FALSE, 'Lieu', -18.9,47.5,'034',30000,FALSE,1,'DEFAULT_SETTING',CURRENT_TIMESTAMP)
                    """, parcel, request);
            if (historical) {
                jdbc.update("""
                        UPDATE delivery_package SET final_outcome='DELIVERED',actual_collected_ariary=30000,
                            outcome_recorded_at=CURRENT_TIMESTAMP,outcome_recorded_by_account_id=?,
                            actual_delivery_courier_id=?,actual_delivery_courier_name='Hery',actual_delivery_vehicle_type='MOTO'
                        WHERE id=?
                        """, admin, courier, parcel);
                jdbc.update("INSERT INTO delivery_operation_day (service_date,status,closure_revision,closed_at,closed_by_account_id) VALUES (?, 'CLOSED',1,CURRENT_TIMESTAMP,?)", date, admin);
                jdbc.update("INSERT INTO delivery_operation_client_snapshot (service_date,closure_revision,account_id,display_name) VALUES (?,1,?,'Vendeur')", date, seller);
                jdbc.update("""
                        INSERT INTO client_daily_settlement (id,report_id,service_date,closure_revision,
                            client_account_id,client_display_name,amount_due_ariary)
                        VALUES (?, ?, ?, 1, ?, 'Vendeur', 25000)
                        """, UUID.randomUUID(), oldReport, date, seller);
            }
            Flyway.configure().dataSource(source).load().migrate();
            tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            service = new CashLedgerService(new CashLedgerRepository(jdbc), proofStorage);
        }

        void account(UUID id, String role) {
            jdbc.update("INSERT INTO app_account (id,username,password_hash,role,display_name) VALUES (?,?,'hash',?,'Compte')", id, "cash-" + id, role);
        }

        TransferRequest transfer(String key, String from, String to, long amount, Long revision) {
            return new TransferRequest(key, from, to, amount, "MVola", null, null, revision);
        }
        <T> T call(Supplier<T> action) { return tx.execute(status -> action.get()); }
        void run(Runnable action) { tx.executeWithoutResult(status -> action.run()); }
        Snapshot snapshot() { return call(service::getSnapshot); }
        long balance(String key) { return snapshot().accounts().stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow().balanceAriary(); }
        boolean race(CountDownLatch gate, String key, long revision) throws InterruptedException {
            gate.await();
            try {
                call(() -> service.confirmTransfer(transfer(key, "AZY", courierKey(courier), 10, revision), admin, null));
                return true;
            } catch (FinanceConflictException expected) { return false; }
        }
        @Override public void close() { source.close(); }
    }
}
