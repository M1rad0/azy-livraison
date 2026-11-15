package com.azylivraison.pricing.domain;

import java.util.UUID;

/** A destination match replaces the zone base price, before weight and time surcharges. */
public record PricingKeywordRule(UUID id, String keyword, long deliveryFeeAriary) {}
