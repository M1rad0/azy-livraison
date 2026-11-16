package com.azylivraison.finance.cash;

import static org.assertj.core.api.Assertions.*;
import static com.azylivraison.finance.cash.CashLedgerModels.*;
import static com.azylivraison.finance.cash.CashLedgerService.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.azylivraison.finance.service.FinanceInputException;
import org.junit.jupiter.api.Test;

/** Real SQL on the same isolated H2 fixture as the ledger persistence suite. */
class CashLedgerPaginationTest {
    @Test void movementDatesAreInclusiveMadagascarDaysAndNeverChangeBalances() {
        try (var f = new CashLedgerPersistenceTest.Fixture(false)) {
            Movement before = adjustment(f, "before", 1);
            Movement first = adjustment(f, "first", 2);
            Movement last = adjustment(f, "last", 4);
            Movement after = adjustment(f, "after", 8);
            setTime(f, before, "2026-11-14T20:59:59.999999Z");
            setTime(f, first, "2026-11-14T21:00:00Z");
            setTime(f, last, "2026-11-15T20:59:59.999999Z");
            setTime(f, after, "2026-11-15T21:00:00Z");
            var query = new CashListQuery(f.date, f.date, null, 0, 1);
            var page = f.service.movements(query);
            assertThat(page.totalElements()).isEqualTo(2);
            assertThat(page.totalPages()).isEqualTo(2);
            assertThat(page.items()).extracting(Movement::id).containsExactly(last.id());
            assertThat(f.service.movements(new CashListQuery(f.date, f.date, null, 1, 1)).items())
                    .extracting(Movement::id).containsExactly(first.id());
            var beyond = f.service.movements(new CashListQuery(f.date, f.date, null, 2, 1));
            assertThat(beyond.items()).isEmpty();
            assertThat(beyond.totalElements()).isEqualTo(2);
            assertThat(f.balance("AZY")).isEqualTo(15);
        }
    }

    @Test void pagingHasStableTiebreakersAndDoesNotLoadOtherPages() {
        try (var f = new CashLedgerPersistenceTest.Fixture(false)) {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 26; i++) {
                Movement movement = adjustment(f, "seed-" + i, 1);
                setTime(f, movement, "2026-11-15T12:00:00Z");
                ids.add(movement.id());
            }
            ids.sort(Comparator.comparing(UUID::toString).reversed());
            var first = f.service.movements(new CashListQuery(null, null, null, 0, 20));
            var second = f.service.movements(new CashListQuery(null, null, null, 1, 20));
            assertThat(first.items()).extracting(Movement::id).containsExactlyElementsOf(ids.subList(0, 20));
            assertThat(second.items()).extracting(Movement::id).containsExactlyElementsOf(ids.subList(20, 26));
            assertThat(first.totalElements()).isEqualTo(26);
            assertThat(second.totalPages()).isEqualTo(2);
            assertThat(f.balance("AZY")).isEqualTo(26);
        }
    }

    @Test void movementSearchUsesAccountNamesReferencesNotesAndLiteralWildcards() {
        try (var f = new CashLedgerPersistenceTest.Fixture(false)) {
            f.call(() -> f.service.confirmTransfer(new TransferRequest("search", "AZY", courierKey(f.courier),
                    500, "MVola", "REFERENCE-123", "remise 10%_ok!", null), f.admin, null));
            for (String term : List.of("hery", "MVoLa", "reference-123", "10%_ok!"))
                assertThat(f.service.movements(new CashListQuery(null, null, term, 0, 20)).totalElements()).isEqualTo(1);
            assertThat(f.service.movements(new CashListQuery(null, null, "%does-not-exist", 0, 20)).items()).isEmpty();
        }
    }

    @Test void reportRowsAreFilteredByServiceDateAndRemainFullyAllocated() {
        try (var f = new CashLedgerPersistenceTest.Fixture(false)) {
            f.jdbc.update("UPDATE app_account SET display_name = 'Vendeur test' WHERE id = ?", f.seller);
            f.jdbc.update("INSERT INTO client_profile (account_id) VALUES (?)", f.seller);
            f.jdbc.update("""
                    INSERT INTO client_payout_destination (id,account_id,operator,account_holder,number,priority)
                    VALUES (?,?,'MVola','Titulaire','+261 34 16 793 43',1)
                    """, UUID.randomUUID(), f.seller);
            UUID oldest = UUID.randomUUID(), middle = UUID.randomUUID(), newest = UUID.randomUUID();
            f.run(() -> f.service.syncSellerReport(oldest, f.date.minusDays(1), f.seller, 100, f.admin));
            f.run(() -> f.service.syncSellerReport(middle, f.date, f.seller, 200, f.admin));
            f.run(() -> f.service.syncSellerReport(newest, f.date.plusDays(1), f.seller, 300, f.admin));
            f.call(() -> f.service.confirmTransfer(f.transfer("paid", "AZY", sellerKey(f.seller), 150, null), f.admin, null));
            for (String term : List.of("vendeur", "0341679343", "+261 34 16 793 43", "00261341679343")) {
                var page = f.service.obligations(new CashListQuery(f.date, f.date.plusDays(1), term, 1, 1));
                assertThat(page.totalElements()).isEqualTo(2);
                assertThat(page.items()).singleElement().satisfies(o -> {
                    assertThat(o.reportId()).isEqualTo(middle);
                    assertThat(o.paidAriary()).isEqualTo(50);
                    assertThat(o.remainingAriary()).isEqualTo(150);
                });
            }
            f.run(() -> f.service.reopenSellerReport(newest, f.admin));
            assertThat(f.service.obligations(new CashListQuery(null, null, null, 0, 20)).items())
                    .hasSize(2).allMatch(Obligation::active);
            assertThat(f.balance(sellerKey(f.seller))).isEqualTo(150);
        }
    }

    @Test void lightSnapshotKeepsRealBalancesAndRevisionWithoutHistoricalArrays() {
        try (var f = new CashLedgerPersistenceTest.Fixture(false)) {
            adjustment(f, "seed", 100);
            f.run(() -> f.service.syncSellerReport(UUID.randomUUID(), f.date, f.seller, 300, f.admin));
            Snapshot full = f.snapshot();
            Snapshot light = f.call(() -> f.service.getSnapshot(false));
            assertThat(light.accounts()).isEqualTo(full.accounts());
            assertThat(light.revision()).isEqualTo(full.revision());
            assertThat(light.activatedAt()).isEqualTo(full.activatedAt());
            assertThat(light.movements()).isEmpty();
            assertThat(light.obligations()).isEmpty();
            assertThat(full.movements()).isNotEmpty();
            assertThat(full.obligations()).isNotEmpty();
        }
    }

    @Test void invalidRangesAndPagingAreRejectedBeforeSql() {
        assertThatThrownBy(() -> new CashListQuery(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 15), null, 0, 20))
                .isInstanceOf(FinanceInputException.class);
        assertThatThrownBy(() -> new CashListQuery(null, null, null, -1, 20)).isInstanceOf(FinanceInputException.class);
        assertThatThrownBy(() -> new CashListQuery(null, null, null, 0, 0)).isInstanceOf(FinanceInputException.class);
        assertThatThrownBy(() -> new CashListQuery(null, null, null, 0, 101)).isInstanceOf(FinanceInputException.class);
        assertThatThrownBy(() -> new CashListQuery(null, null, "a".repeat(201), 0, 20)).isInstanceOf(FinanceInputException.class);
        assertThat(new CashListQuery(null, null, null, Integer.MAX_VALUE, 100).offset()).isEqualTo(214748364700L);
    }

    private Movement adjustment(CashLedgerPersistenceTest.Fixture f, String note, long amount) {
        return f.call(() -> f.service.adjust(new AdjustmentRequest(note, "AZY", amount, note, null), f.admin));
    }

    private void setTime(CashLedgerPersistenceTest.Fixture f, Movement movement, String instant) {
        f.jdbc.update("UPDATE cash_ledger_movement SET created_at = ? WHERE id = ?", Timestamp.from(Instant.parse(instant)), movement.id());
    }
}
