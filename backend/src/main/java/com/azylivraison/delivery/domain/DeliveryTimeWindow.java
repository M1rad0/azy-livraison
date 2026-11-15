package com.azylivraison.delivery.domain;

import java.time.LocalTime;

import com.azylivraison.pricing.service.PricingConfigurationException;

/** Normalized window; an end before its start denotes the following day. */
public record DeliveryTimeWindow(LocalTime start, LocalTime end, DeliveryTimePreference preference) {

    public static DeliveryTimeWindow normalize(LocalTime start, LocalTime end,
            DeliveryTimePreference preference) {
        boolean legacy = preference == null && start != null && end == null;
        DeliveryTimePreference mode = preference == null
                ? (start == null ? DeliveryTimePreference.NONE : DeliveryTimePreference.CUSTOM) : preference;
        if (mode == DeliveryTimePreference.NONE) {
            if (start != null || end != null) invalid();
            return new DeliveryTimeWindow(null, null, mode);
        }
        if (mode == DeliveryTimePreference.MORNING || mode == DeliveryTimePreference.AFTERNOON) {
            LocalTime normalizedStart = mode == DeliveryTimePreference.MORNING ? LocalTime.MIN : LocalTime.NOON;
            LocalTime normalizedEnd = mode == DeliveryTimePreference.MORNING ? LocalTime.NOON : LocalTime.of(23, 59, 59);
            if ((start != null && !start.equals(normalizedStart)) || (end != null && !end.equals(normalizedEnd))) invalid();
            return new DeliveryTimeWindow(normalizedStart, normalizedEnd, mode);
        }
        if (legacy) return new DeliveryTimeWindow(start, start.plusHours(1), mode);
        if (start == null || end == null || end.equals(start)) invalid();
        return new DeliveryTimeWindow(start, end, mode);
    }

    public boolean endsNextDay() {
        return start != null && end != null && !end.isAfter(start);
    }

    private static void invalid() {
        throw new PricingConfigurationException("Le creneau personnalise doit comporter un debut et une fin differents (duree inferieure a 24 heures).");
    }
}
