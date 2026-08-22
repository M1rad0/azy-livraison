package com.azylivraison.dispatch.domain;

import java.util.UUID;

import com.azylivraison.planning.domain.Courier;

public record SelectedCourierSnapshot(
        UUID profileId,
        String contact,
        Courier courier) {
}
