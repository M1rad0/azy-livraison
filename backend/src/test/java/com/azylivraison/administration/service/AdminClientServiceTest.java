package com.azylivraison.administration.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import com.azylivraison.account.api.UpdateClientPayoutDestinationsRequest.Item;
import com.azylivraison.account.api.UpdateClientContactNumbersRequest;
import com.azylivraison.account.api.DefaultPickupRequest;
import com.azylivraison.account.domain.DefaultPickupLocation;
import com.azylivraison.account.repository.AccountRepository;
import com.azylivraison.account.repository.ClientProfileRepository;
import com.azylivraison.account.service.ClientProfileService;
import com.azylivraison.account.service.InvalidClientProfileException;
import com.azylivraison.account.service.InvalidPayoutOperatorException;
import com.azylivraison.administration.api.AdminClientRequest;
import com.azylivraison.administration.api.AdminClientResponse;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.validation.Validation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Isolated SQL checks: never writes to the application's database. */
class AdminClientServiceTest {
    HikariDataSource database;
    JdbcTemplate jdbc;
    TransactionTemplate transactions;
    AccountRepository accounts;
    ClientProfileRepository profiles;
    AdminClientService service;

    @BeforeEach void setup() {
        database = new HikariDataSource();
        database.setJdbcUrl("jdbc:h2:mem:admin_client_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        database.setUsername("sa"); database.setPassword("");
        Flyway.configure().dataSource(database).load().migrate();
        jdbc = new JdbcTemplate(database);
        transactions = new TransactionTemplate(new JdbcTransactionManager(database));
        accounts = new AccountRepository(jdbc);
        profiles = new ClientProfileRepository(jdbc);
        service = new AdminClientService(accounts, profiles, new BCryptPasswordEncoder(4));
    }

    @AfterEach void teardown() { database.close(); }
    <T> T tx(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    Item payout(String number, int priority) {
        return new Item(null, "MVola", "Boutique Soa", number, priority, true);
    }
    AdminClientRequest request(Long version, List<Item> destinations) {
        return new AdminClientRequest("boutique-soa", "Boutique Soa", "034 00 000 00", true,
                false, "Test-password-123", version, destinations);
    }
    AdminClientResponse create(List<Item> destinations) {
        return tx(() -> service.create(request(null, destinations))).client();
    }

    @Test void creationStoresAndReturnsTheSamePrioritizedDestinationsAsTheClientProfile() {
        Item first = new Item(null, " MVola ", " Soa ", " 034 11 111 11 ", 1, true);
        Item second = new Item(null, " Orange Money ", " Soa ", " 032 22 222 22 ", 2, false);
        AdminClientResponse created = create(List.of(first, second));

        assertThat(created.payoutDestinations()).hasSize(2);
        assertThat(created.payoutDestinations().getFirst()).satisfies(destination -> {
            assertThat(destination.id()).isNotNull();
            assertThat(destination.operator()).isEqualTo("MVola");
            assertThat(destination.accountHolder()).isEqualTo("Soa");
            assertThat(destination.number()).isEqualTo("034 11 111 11");
            assertThat(destination.priority()).isEqualTo(1);
            assertThat(destination.active()).isTrue();
        });
        assertThat(created.payoutDestinations().getLast().active()).isFalse();
        assertThat(profiles.findByAccountId(created.accountId()).orElseThrow().payoutDestinations())
                .containsExactlyElementsOf(created.payoutDestinations());
        assertThat(tx(service::findAll)).singleElement().extracting(AdminClientResponse::payoutDestinations)
                .isEqualTo(created.payoutDestinations());
    }

    @Test void adminCreationAndUpdatesAcceptOnlyCanonicalMobileMoneyOperators() {
        assertThatThrownBy(() -> create(List.of(new Item(null, "Banque", "Soa", "0321415216", 1, true))))
                .isInstanceOf(InvalidPayoutOperatorException.class);
        assertThat(accounts.findClients()).isEmpty();

        var created = create(List.of(new Item(null, "ORANGE_MONEY", "Soa", "0321415216", 1, true)));
        assertThat(created.payoutDestinations().getFirst().operator()).isEqualTo("Orange Money");
        var updated = tx(() -> service.update(created.accountId(), request(created.version(),
                List.of(new Item(null, "airtel-money", "Soa", "0331415216", 1, true)))));
        assertThat(updated.payoutDestinations().getFirst().operator()).isEqualTo("Airtel Money");

        assertThatThrownBy(() -> tx(() -> service.update(created.accountId(), request(updated.version(),
                List.of(new Item(null, "Autre", "Soa", "0331415216", 1, true))))))
                .isInstanceOf(InvalidPayoutOperatorException.class);
        assertThat(tx(service::findAll).getFirst().payoutDestinations()).isEqualTo(updated.payoutDestinations());
    }

    @Test void createsACompleteClientWithContactsPickupPermissionsAndAnInactiveAccount() {
        var contacts = List.of(
                new UpdateClientContactNumbersRequest.Item(null, " Secondaire ", " 032 11 111 11 ", 1, false),
                new UpdateClientContactNumbersRequest.Item(null, " Principal ", " 034 22 222 22 ", 2, true));
        var request = new AdminClientRequest("boutique-soa", " Boutique Soa ", "034 00 000 00", false, true,
                null, null, List.of(payout("034 22 222 22", 1)), contacts,
                new DefaultPickupRequest(" Boutique ", " Analakely ", -18.9, 47.5), false);
        var result = tx(() -> service.create(request));

        assertThat(result.temporaryPassword()).startsWith("Azy-");
        assertThat(result.client().enabled()).isFalse();
        assertThat(result.client().expressDeliveryEnabled()).isTrue();
        assertThat(result.client().phone()).isEqualTo("034 22 222 22");
        assertThat(result.client().contactNumbers()).hasSize(2);
        assertThat(result.client().contactNumbers().getFirst().active()).isFalse();
        assertThat(result.client().defaultPickup()).isEqualTo(
                new AdminClientResponse.DefaultPickupResponse("Boutique", "Analakely", -18.9, 47.5));
    }

    @Test void updatesAllCoordinatesWithOneVersionAndAllowsExplicitPickupRemoval() {
        var created = create(List.of(payout("034 11 111 11", 1)));
        UUID contactId = created.contactNumbers().getFirst().id();
        var request = new AdminClientRequest("boutique-soa", "Nouveau nom", "034 00 000 00", true, true,
                null, created.version(), null,
                List.of(new UpdateClientContactNumbersRequest.Item(contactId, "Boutique", "032 99 999 99", 1, true)),
                new DefaultPickupRequest("Retrait", "Analakely", -18.9, 47.5), false);
        var updated = tx(() -> service.update(created.accountId(), request));

        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertThat(updated.contactNumbers().getFirst().id()).isEqualTo(contactId);
        assertThat(updated.phone()).isEqualTo("032 99 999 99");
        assertThat(updated.payoutDestinations()).isEqualTo(created.payoutDestinations());
        var withoutPickup = new AdminClientRequest("boutique-soa", "Nouveau nom", updated.phone(), true, true,
                null, updated.version(), null, null, null, true);
        var cleared = tx(() -> service.update(created.accountId(), withoutPickup));
        assertThat(cleared.defaultPickup()).isNull();
        assertThat(cleared.contactNumbers()).isEqualTo(updated.contactNumbers());
    }

    @Test void rejectsInvalidContactsAndPartialPickupBeforeCreatingAnyAccount() {
        var duplicate = List.of(
                new UpdateClientContactNumbersRequest.Item(null, "Principal", "034 11", 1, true),
                new UpdateClientContactNumbersRequest.Item(null, "Autre", "03411", 2, true));
        var request = new AdminClientRequest("boutique-soa", "Soa", "034 00", true, false,
                null, null, null, duplicate, null, null);
        assertThatThrownBy(() -> tx(() -> service.create(request))).isInstanceOf(InvalidClientProfileException.class);
        var missingLongitude = new AdminClientRequest("boutique-soa", "Soa", "034 00", true, false,
                null, null, null, null, new DefaultPickupRequest("Retrait", "Analakely", -18.9, null), false);
        assertThatThrownBy(() -> tx(() -> service.create(missingLongitude))).isInstanceOf(InvalidClientProfileException.class);
        assertThat(accounts.findClients()).isEmpty();
    }

    @Test void updatingPayoutsUsesTheExistingVersionAndPreservesPickupAndContacts() {
        AdminClientResponse created = create(List.of(payout("034 11 111 11", 1)));
        UUID id = created.accountId();
        DefaultPickupLocation pickup = new DefaultPickupLocation("Boutique", "Analakely", -18.9, 47.5);
        tx(() -> { profiles.updateDefaultPickup(id, pickup); return null; });
        long before = profiles.findByAccountId(id).orElseThrow().version();
        UUID destinationId = created.payoutDestinations().getFirst().id();
        Item modified = new Item(destinationId, "MVola", "Soa", "034 99 999 99", 1, true);
        AdminClientResponse updated = tx(() -> service.update(id, request(before, List.of(modified))));

        assertThat(updated.version()).isEqualTo(before + 1);
        assertThat(updated.payoutDestinations()).singleElement().satisfies(destination -> {
            assertThat(destination.id()).isEqualTo(destinationId);
            assertThat(destination.number()).isEqualTo("034 99 999 99");
        });
        var profile = profiles.findByAccountId(id).orElseThrow();
        assertThat(profile.defaultPickup()).isEqualTo(pickup);
        assertThat(profile.contactNumbers()).singleElement().satisfies(contact ->
                assertThat(contact.number()).isEqualTo("034 00 000 00"));
    }

    @Test void omittedDestinationsRemainBackwardCompatibleAndAnExplicitEmptyListClearsThem() {
        AdminClientResponse created = create(List.of(payout("034 11 111 11", 1)));
        AdminClientResponse kept = tx(() -> service.update(created.accountId(), request(created.version(), null)));
        assertThat(kept.payoutDestinations()).isEqualTo(created.payoutDestinations());

        AdminClientResponse cleared = tx(() -> service.update(created.accountId(), request(kept.version(), List.of())));
        assertThat(cleared.payoutDestinations()).isEmpty();
        assertThat(profiles.findByAccountId(created.accountId()).orElseThrow().payoutDestinations()).isEmpty();
    }

    @Test void concurrentProfileEditsCannotOverwriteDestinationsOrAnyOtherAccountInformation() {
        AdminClientResponse created = create(List.of(payout("034 11 111 11", 1)));
        tx(() -> service.update(created.accountId(), request(created.version(), List.of(payout("034 22 222 22", 1)))));
        AdminClientRequest stale = new AdminClientRequest("boutique-soa", "Incorrect", "032 00 000 00",
                false, true, null, created.version(), List.of(payout("034 99 999 99", 1)));

        assertThatThrownBy(() -> tx(() -> service.update(created.accountId(), stale)))
                .isInstanceOf(AdministrationConflictException.class);
        assertThat(accounts.findById(created.accountId()).orElseThrow()).satisfies(account -> {
            assertThat(account.displayName()).isEqualTo("Boutique Soa");
            assertThat(account.phone()).isEqualTo("034 00 000 00");
            assertThat(account.enabled()).isTrue();
        });
        var profile = profiles.findByAccountId(created.accountId()).orElseThrow();
        assertThat(profile.expressDeliveryEnabled()).isFalse();
        assertThat(profile.payoutDestinations()).singleElement().satisfies(destination ->
                assertThat(destination.number()).isEqualTo("034 22 222 22"));
    }

    @Test void sharedProfileRulesRejectDuplicateNumbersDuplicateIdsAndInvalidPrioritiesBeforeSaving() {
        assertThatThrownBy(() -> create(List.of(payout("034 11 111 11", 1),
                new Item(null, " mVoLa ", "Soa", " 034 11 111 11 ", 2, true))))
                .isInstanceOf(InvalidClientProfileException.class);
        assertThatThrownBy(() -> create(List.of(payout("034 11 111 11", 2))))
                .isInstanceOf(InvalidClientProfileException.class);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> create(List.of(
                new Item(id, "MVola", "Soa", "034 11 111 11", 1, true),
                new Item(id, "MVola", "Soa", "034 22 222 22", 2, true))))
                .isInstanceOf(InvalidClientProfileException.class);
        assertThat(accounts.findClients()).isEmpty();
    }

    @Test void adminAndClientEditsShareTheSameStoredNumbersAndOptimisticVersion() {
        AdminClientResponse created = create(List.of(payout("034 11 111 11", 1)));
        ClientProfileService clientService = new ClientProfileService(accounts, profiles);
        var changedByClient = tx(() -> clientService.replacePayoutDestinations("boutique-soa", created.version(),
                List.of(payout("034 22 222 22", 1))));

        AdminClientResponse listed = tx(service::findAll).getFirst();
        assertThat(listed.payoutDestinations()).isEqualTo(changedByClient.profile().payoutDestinations());
        assertThat(listed.version()).isEqualTo(changedByClient.profile().version());
        assertThatThrownBy(() -> tx(() -> service.update(created.accountId(),
                request(created.version(), List.of(payout("034 99 999 99", 1))))))
                .isInstanceOf(AdministrationConflictException.class);
    }

    @Test void adminRequestValidatesNestedDestinationsWithoutRequiringThemForOlderClients() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(request(null, null))).isEmpty();
            assertThat(validator.validate(request(null, List.of()))).isEmpty();
            assertThat(validator.validate(request(null, List.of(payout(" ", 1))))).isNotEmpty();
            assertThat(validator.validate(request(null, List.of(payout("0".repeat(101), 1))))).isNotEmpty();
            assertThat(validator.validate(request(null, IntStream.rangeClosed(1, 21)
                    .mapToObj(index -> payout("034" + index, index)).toList()))).isNotEmpty();
            assertThat(validator.validate(request(null, java.util.Arrays.asList((Item) null)))).isNotEmpty();
        }
    }
}
