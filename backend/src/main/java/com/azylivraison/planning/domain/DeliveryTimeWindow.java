package com.azylivraison.planning.domain;

import java.time.OffsetDateTime;

public record DeliveryTimeWindow(
        OffsetDateTime startAt,
        OffsetDateTime endAt) {
}
