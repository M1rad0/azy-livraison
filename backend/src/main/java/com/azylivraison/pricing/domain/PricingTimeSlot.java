package com.azylivraison.pricing.domain;

import java.time.LocalTime;
import java.util.UUID;

public record PricingTimeSlot(
        UUID id,
        String label,
        LocalTime startTime,
        LocalTime endTime,
        long surchargeAriary) {
}
