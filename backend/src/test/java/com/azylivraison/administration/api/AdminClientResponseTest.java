package com.azylivraison.administration.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.azylivraison.account.domain.Account;
import com.azylivraison.account.domain.AccountRole;
import com.azylivraison.account.domain.ClientProfile;
import com.azylivraison.account.domain.ClientPayoutDestination;
import com.azylivraison.account.domain.DefaultPickupLocation;

class AdminClientResponseTest {

    @Test
    void exposesTheClientsDefaultPickupToTheAdministrationInterface() {
        UUID accountId = UUID.randomUUID();
        Account account = new Account(
                accountId, "boutique-soa", "hash", true, AccountRole.CLIENT,
                "Boutique Soa", "034 00 000 00");
        ClientProfile profile = new ClientProfile(
                accountId,
                new DefaultPickupLocation(
                        "Boutique Analakely", "Analakely, Antananarivo", -18.9089, 47.5212),
                true,
                List.of(),
                List.of(),
                3);

        AdminClientResponse response = AdminClientResponse.from(account, profile);

        assertThat(response.defaultPickup()).isEqualTo(
                new AdminClientResponse.DefaultPickupResponse(
                        "Boutique Analakely", "Analakely, Antananarivo", -18.9089, 47.5212));
    }

    @Test
    void keepsTheDefaultPickupAbsentWhenTheClientHasNone() {
        UUID accountId = UUID.randomUUID();
        Account account = new Account(
                accountId, "boutique-soa", "hash", true, AccountRole.CLIENT,
                "Boutique Soa", "034 00 000 00");
        ClientProfile profile = new ClientProfile(
                accountId, null, false, List.of(), List.of(), 0);

        assertThat(AdminClientResponse.from(account, profile).defaultPickup()).isNull();
    }

    @Test
    void exposesTheOrderedPayoutDestinationsForAdministrationEdits() {
        UUID accountId = UUID.randomUUID();
        Account account = new Account(accountId, "boutique-soa", "hash", true, AccountRole.CLIENT,
                "Boutique Soa", "034 00 000 00");
        var destinations = List.of(
                new ClientPayoutDestination(UUID.randomUUID(), "MVola", "Soa", "034 11 111 11", 1, true),
                new ClientPayoutDestination(UUID.randomUUID(), "Orange Money", "Soa", "032 22 222 22", 2, false));
        ClientProfile profile = new ClientProfile(accountId, null, false, List.of(), destinations, 3);

        assertThat(AdminClientResponse.from(account, profile).payoutDestinations()).containsExactlyElementsOf(destinations);
    }
}
