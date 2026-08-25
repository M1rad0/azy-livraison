package com.azylivraison.dispatch.service;

import java.util.UUID;

public class DispatchExecutionException extends RuntimeException {
    private final UUID dispatchPlanId;

    public DispatchExecutionException(UUID dispatchPlanId, String message, Throwable cause) {
        super(message, cause);
        this.dispatchPlanId = dispatchPlanId;
    }

    public UUID dispatchPlanId() {
        return dispatchPlanId;
    }
}
