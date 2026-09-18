package com.azylivraison.delivery.domain;

public enum ExpressFuelCalculationStatus {
    WAITING_FOR_OUTCOMES,
    CALCULATED,
    ROUTING_FAILED,
    CONFIGURATION_MISSING,
    MANUAL_INCOMPLETE,
    MANUAL
}
