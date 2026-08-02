package com.azylivraison.account.service;

import java.util.UUID;
import java.util.Locale;

import com.azylivraison.account.config.AuthProperties;
import com.azylivraison.account.domain.Account;
import com.azylivraison.account.repository.AccountRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class InitialAccountBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitialAccountBootstrap.class);

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public InitialAccountBootstrap(
            AccountRepository accountRepository,
            PasswordEncoder passwordEncoder,
            AuthProperties properties) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String username = properties.initialUsername().trim().toLowerCase(Locale.ROOT);
        if (accountRepository.findByUsername(username).isPresent()) {
            return;
        }

        try {
            accountRepository.create(new Account(
                    UUID.randomUUID(),
                    username,
                    passwordEncoder.encode(properties.initialPassword()),
                    true));
            LOGGER.info("Compte initial '{}' créé.", username);
        } catch (DuplicateKeyException exception) {
            LOGGER.info("Le compte initial '{}' a déjà été créé par une autre instance.", username);
        }
    }
}
