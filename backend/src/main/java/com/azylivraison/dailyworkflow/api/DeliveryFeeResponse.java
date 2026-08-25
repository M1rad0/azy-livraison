package com.azylivraison.dailyworkflow.api;

import java.time.Instant;
import java.util.UUID;

import com.azylivraison.dailyworkflow.domain.DeliveryFeeMode;
import com.azylivraison.dailyworkflow.repository.DailyWorkflowRepository.DeliveryFeeRecord;

public record DeliveryFeeResponse(
        UUID packageId,
        Long calculatedFeeAriary,
        Long finalFeeAriary,
        DeliveryFeeMode mode,
        String overrideReason,
        Instant overriddenAt,
        UUID overriddenByAccountId,
        long feeVersion,
        long version) {

    static DeliveryFeeResponse from(DeliveryFeeRecord record) {
        return new DeliveryFeeResponse(
                record.packageId(),
                record.calculatedFeeAriary(),
                record.finalFeeAriary(),
                record.overrideReason() == null ? DeliveryFeeMode.CALCULATED : DeliveryFeeMode.MANUAL,
                record.overrideReason(),
                record.overriddenAt(),
                record.overriddenByAccountId(),
                record.feeVersion(),
                record.version());
    }
}
