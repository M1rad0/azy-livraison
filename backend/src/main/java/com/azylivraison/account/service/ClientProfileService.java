package com.azylivraison.account.service;

import com.azylivraison.account.api.DefaultPickupRequest;
import com.azylivraison.account.domain.Account;
import com.azylivraison.account.domain.AccountRole;
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
        clientProfileRepository.updateDefaultPickup(account.id(), pickup);
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

    public record ProfileSnapshot(Account account, ClientProfile profile) {
    }
}
