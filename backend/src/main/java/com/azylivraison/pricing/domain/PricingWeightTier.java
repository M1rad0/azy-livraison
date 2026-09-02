package com.azylivraison.pricing.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record PricingWeightTier(UUID id, BigDecimal maxWeightKg, long surchargeAriary) {
}
