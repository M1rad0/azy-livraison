package com.azylivraison.delivery.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ExpressFuelCalculationListener {

    private final ExpressFuelService service;

    public ExpressFuelCalculationListener(ExpressFuelService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void calculate(ExpressOutcomesFinalizedEvent event) {
        service.recalculate(event.requestId(), null);
    }
}
