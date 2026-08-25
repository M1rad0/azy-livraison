package com.azylivraison.dispatch.domain;

import java.util.UUID;

public record ManualDispatchAssignment(
        UUID packageId,
        UUID distributorCourierId,
        int deliveryOrder) {
}
