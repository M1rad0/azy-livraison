package com.azylivraison.administration.service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.azylivraison.account.domain.Account;
import com.azylivraison.account.domain.AccountRole;
import com.azylivraison.account.domain.ClientProfile;
import com.azylivraison.account.domain.ClientPayoutDestination;
import com.azylivraison.account.repository.AccountRepository;
import com.azylivraison.account.repository.ClientProfileRepository;
import com.azylivraison.account.service.ClientPayoutDestinationNormalizer;
import com.azylivraison.administration.api.AdminClientRequest;
import com.azylivraison.administration.api.AdminClientResponse;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminClientService {

    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private final AccountRepository accounts;
    private final ClientProfileRepository profiles;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public AdminClientService(AccountRepository accounts, ClientProfileRepository profiles,
            PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<AdminClientResponse> findAll() {
        return accounts.findClients().stream().map(this::response).toList();
    }

    @Transactional
    public CreationResult create(AdminClientRequest request) {
        List<ClientPayoutDestination> destinations = normalizePayoutDestinations(request);
        String temporaryPassword = normalizePassword(request.temporaryPassword());
        Account account = new Account(UUID.randomUUID(), request.username().trim().toLowerCase(Locale.ROOT),
                passwordEncoder.encode(temporaryPassword), true, AccountRole.CLIENT,
                request.displayName().trim(), request.phone().trim());
        try {
            accounts.create(account);
            profiles.create(account.id());
            profiles.createPrimaryContact(account.id(), account.phone());
            if (Boolean.TRUE.equals(request.expressDeliveryEnabled())) {
                profiles.updateExpressDeliveryPermission(account.id(), true, 0);
            }
            if (destinations != null) {
                profiles.replacePayoutDestinations(account.id(), destinations);
            }
        } catch (DuplicateKeyException exception) {
            throw new AdministrationConflictException("CLIENT_USERNAME_EXISTS",
                    "Cet identifiant est deja utilise.");
        }
        return new CreationResult(response(accounts.findById(account.id()).orElseThrow()), temporaryPassword);
    }

    @Transactional
    public AdminClientResponse update(UUID accountId, AdminClientRequest request) {
        Account current = client(accountId);
        ClientProfile profile = profile(accountId);
        List<ClientPayoutDestination> destinations = normalizePayoutDestinations(request);
        long expectedVersion = request.version() == null ? profile.version() : request.version();
        accounts.updateClient(accountId, request.displayName().trim(), request.phone().trim(),
                !Boolean.FALSE.equals(request.enabled()));
        if (!profiles.updateExpressDeliveryPermission(accountId,
                Boolean.TRUE.equals(request.expressDeliveryEnabled()), expectedVersion)) {
            throw new AdministrationConflictException("CLIENT_VERSION_CONFLICT",
                    "Ce client a ete modifie entre-temps. Rechargez la liste.");
        }
        if (!current.phone().equals(request.phone().trim())) {
            profiles.updatePrimaryContact(accountId, request.phone().trim());
        }
        if (destinations != null) {
            profiles.replacePayoutDestinations(accountId, destinations);
        }
        return response(accounts.findById(accountId).orElseThrow());
    }

    @Transactional
    public String resetPassword(UUID accountId) {
        client(accountId);
        String password = generatePassword();
        accounts.updatePassword(accountId, passwordEncoder.encode(password));
        return password;
    }

    private AdminClientResponse response(Account account) {
        return AdminClientResponse.from(account, profile(account.id()));
    }

    private Account client(UUID id) {
        return accounts.findById(id).filter(account -> account.role() == AccountRole.CLIENT)
                .orElseThrow(() -> new AdministrationNotFoundException(
                        "CLIENT_NOT_FOUND", "Le client demande n'existe pas."));
    }

    private ClientProfile profile(UUID id) {
        return profiles.findByAccountId(id).orElseThrow(() -> new AdministrationNotFoundException(
                "CLIENT_NOT_FOUND", "Le profil client demande n'existe pas."));
    }

    private String normalizePassword(String requested) {
        return requested == null || requested.isBlank() ? generatePassword() : requested;
    }

    private List<ClientPayoutDestination> normalizePayoutDestinations(AdminClientRequest request) {
        return request.payoutDestinations() == null ? null
                : ClientPayoutDestinationNormalizer.normalize(request.payoutDestinations());
    }

    private String generatePassword() {
        StringBuilder password = new StringBuilder("Azy-");
        for (int index = 0; index < 10; index++) {
            password.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    public record CreationResult(AdminClientResponse client, String temporaryPassword) {}
}
