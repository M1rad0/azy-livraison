package com.azylivraison.account.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.azylivraison.account.api.DefaultPickupRequest;
import com.azylivraison.account.api.UpdateClientContactNumbersRequest;
import com.azylivraison.account.api.UpdateClientPayoutDestinationsRequest;
import com.azylivraison.account.domain.Account;
import com.azylivraison.account.domain.AccountRole;
import com.azylivraison.account.domain.ClientContactNumber;
import com.azylivraison.account.domain.ClientPayoutDestination;
import com.azylivraison.account.domain.ClientProfile;
import com.azylivraison.account.domain.DefaultPickupLocation;
import com.azylivraison.account.repository.AccountRepository;
import com.azylivraison.account.repository.ClientProfileRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientProfileService {

    private final AccountRepository accountRepository;
    private final ClientProfileRepository clientProfileRepository;

    public ClientProfileService(
            AccountRepository accountRepository,
            ClientProfileRepository clientProfileRepository) {
        this.accountRepository = accountRepository;
        this.clientProfileRepository = clientProfileRepository;
    }

    @Transactional(readOnly = true)
    public ProfileSnapshot get(String username) {
        Account account = clientAccount(username);
        ClientProfile profile = clientProfileRepository.findByAccountId(account.id())
                .orElseThrow(ClientProfileNotFoundException::new);
        return new ProfileSnapshot(account, profile);
    }

    @Transactional
    public ProfileSnapshot update(
            String username,
            String displayName,
            String phone,
            DefaultPickupRequest pickupRequest) {
        Account account = clientAccount(username);
        DefaultPickupLocation pickup = normalizePickup(pickupRequest);
        accountRepository.updateProfile(account.id(), displayName.trim(), phone.trim());
        clientProfileRepository.updatePrimaryContact(account.id(), phone.trim());
        clientProfileRepository.updateDefaultPickup(account.id(), pickup);
        return get(username);
    }

    @Transactional
    public ProfileSnapshot replaceContactNumbers(
            String username,
            long expectedVersion,
            List<UpdateClientContactNumbersRequest.Item> requestedItems) {
        Account account = clientAccount(username);
        List<ClientContactNumber> contacts = normalizeContacts(requestedItems);
        if (contacts.stream().noneMatch(ClientContactNumber::active)) {
            throw new InvalidClientProfileException("Conservez au moins un numéro de contact actif.");
        }
        if (!clientProfileRepository.bumpVersion(account.id(), expectedVersion)) {
            throw new ClientProfileVersionConflictException();
        }
        clientProfileRepository.replaceContactNumbers(account.id(), contacts);
        String primaryPhone = contacts.stream()
                .filter(ClientContactNumber::active)
                .findFirst()
                .orElseThrow()
                .number();
        accountRepository.updatePhone(account.id(), primaryPhone);
        return get(username);
    }

    @Transactional
    public ProfileSnapshot replacePayoutDestinations(
            String username,
            long expectedVersion,
            List<UpdateClientPayoutDestinationsRequest.Item> requestedItems) {
        Account account = clientAccount(username);
        List<ClientPayoutDestination> destinations = ClientPayoutDestinationNormalizer.normalize(requestedItems);
        if (!clientProfileRepository.bumpVersion(account.id(), expectedVersion)) {
            throw new ClientProfileVersionConflictException();
        }
        clientProfileRepository.replacePayoutDestinations(account.id(), destinations);
        return get(username);
    }

    private Account clientAccount(String username) {
        return accountRepository.findByUsername(username)
                .filter(Account::enabled)
                .filter(account -> account.role() == AccountRole.CLIENT)
                .orElseThrow(ClientProfileNotFoundException::new);
    }

    private DefaultPickupLocation normalizePickup(DefaultPickupRequest request) {
        if (request == null) {
            return null;
        }
        boolean hasLatitude = request.latitude() != null;
        boolean hasLongitude = request.longitude() != null;
        if (hasLatitude != hasLongitude) {
            throw new InvalidClientProfileException(
                    "La latitude et la longitude du point de retrait doivent être fournies ensemble.");
        }
        String label = trimToNull(request.label());
        String query = trimToNull(request.destinationQuery());
        if (!hasLatitude && query == null) {
            throw new InvalidClientProfileException(
                    "Le point de retrait doit contenir une recherche textuelle ou des coordonnées.");
        }
        return new DefaultPickupLocation(label, query, request.latitude(), request.longitude());
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<ClientContactNumber> normalizeContacts(
            List<UpdateClientContactNumbersRequest.Item> requestedItems) {
        requireOrderedPriorities(requestedItems.stream().map(UpdateClientContactNumbersRequest.Item::priority).toList());
        Set<UUID> ids = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        return requestedItems.stream().map(item -> {
            UUID id = item.id() == null ? UUID.randomUUID() : item.id();
            if (!ids.add(id)) {
                throw new InvalidClientProfileException("Un numéro de contact est présent plusieurs fois.");
            }
            String number = item.number().trim();
            if (!numbers.add(number)) {
                throw new InvalidClientProfileException("Deux contacts utilisent le même numéro.");
            }
            return new ClientContactNumber(
                    id, trimToNull(item.label()), number, item.priority(), item.active());
        }).toList();
    }

    private void requireOrderedPriorities(List<Integer> priorities) {
        for (int index = 0; index < priorities.size(); index++) {
            if (priorities.get(index) == null || priorities.get(index) != index + 1) {
                throw new InvalidClientProfileException(
                        "Les priorités doivent être uniques et ordonnées de 1 à " + priorities.size() + ".");
            }
        }
    }

    public record ProfileSnapshot(Account account, ClientProfile profile) {
    }
}
