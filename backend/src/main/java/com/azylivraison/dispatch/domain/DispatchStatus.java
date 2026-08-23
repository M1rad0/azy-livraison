package com.azylivraison.dispatch.domain;

public enum DispatchStatus {
    GENERATING,
    READY,
    READY_WITH_WARNINGS,
    FAILED,
    CANCELLED,
    SUPERSEDED
}
