package com.azylivraison.administration.service;

import com.azylivraison.administration.domain.OperationalSettings;
import com.azylivraison.administration.repository.OperationalSettingsRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalSettingsService {

    private final OperationalSettingsRepository repository;

    public OperationalSettingsService(OperationalSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public OperationalSettings get() {
        return repository.find().orElseThrow(() -> new IllegalStateException(
                "Les paramètres opérationnels n'ont pas été initialisés."));
    }

    @Transactional
    public OperationalSettings update(OperationalSettings requested, Long requestedVersion) {
        OperationalSettings current = get();
        long expectedVersion = requestedVersion == null ? current.version() : requestedVersion;
        OperationalSettings toSave = new OperationalSettings(
                requested.dailyPackageLimit(),
                requested.defaultHub(),
                requested.defaultPackageWeightKg(),
                requested.pickupServiceMinutes(),
                requested.hubServiceMinutes(),
                requested.deliveryServiceMinutes(),
                requested.earlyToleranceMinutes(),
                requested.lateToleranceMinutes(),
                requested.optimizationTimeLimitSeconds(),
                requested.routingPreset(),
                reduceNumerator(
                        requested.defaultCourierCommissionNumerator(),
                        requested.defaultCourierCommissionDenominator()),
                reduceDenominator(
                        requested.defaultCourierCommissionNumerator(),
                        requested.defaultCourierCommissionDenominator()),
                current.version(),
                current.createdAt(),
                current.updatedAt());

        if (!repository.update(toSave, expectedVersion)) {
            throw staleConfiguration();
        }
        return get();
    }

    private AdministrationConflictException staleConfiguration() {
        return new AdministrationConflictException(
                "SETTINGS_VERSION_CONFLICT",
                "Les paramètres ont été modifiés entre-temps. Rechargez la page avant de réessayer.");
    }

    private int reduceNumerator(int numerator, int denominator) {
        return numerator / greatestCommonDivisor(numerator, denominator);
    }

    private int reduceDenominator(int numerator, int denominator) {
        return denominator / greatestCommonDivisor(numerator, denominator);
    }

    private int greatestCommonDivisor(int left, int right) {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }
}
