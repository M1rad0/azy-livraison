package com.azylivraison.dispatch.domain;

public record DispatchCreationResult(
        DispatchPlanDetails details,
        boolean replayed) {

    public static DispatchCreationResult created(DispatchPlanDetails details) {
        return new DispatchCreationResult(details, false);
    }

    public static DispatchCreationResult replayed(DispatchPlanDetails details) {
        return new DispatchCreationResult(details, true);
    }
}
