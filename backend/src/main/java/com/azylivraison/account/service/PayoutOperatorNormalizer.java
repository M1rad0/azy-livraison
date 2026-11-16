package com.azylivraison.account.service;

import java.util.Locale;
import java.util.Optional;

/** Allowed operators for newly entered payout destinations; historical snapshots stay untouched. */
public final class PayoutOperatorNormalizer {
    private PayoutOperatorNormalizer() {}

    public static Optional<String> supportedName(String value) {
        if (value == null) return Optional.empty();
        String key = value.toUpperCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
        return switch (key) {
            case "MVOLA" -> Optional.of("MVola");
            case "ORANGEMONEY" -> Optional.of("Orange Money");
            case "AIRTELMONEY" -> Optional.of("Airtel Money");
            default -> Optional.empty();
        };
    }

    public static String normalize(String value) {
        return supportedName(value).orElseThrow(InvalidPayoutOperatorException::new);
    }
}
