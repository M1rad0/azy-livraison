package com.azylivraison.finance.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Historical earnings remain readable/payable; salaried couriers accrue no new commission or fuel. */
@Component
public class LegacyFinancePolicy {
    private final boolean accrualsEnabled;

    public LegacyFinancePolicy(@Value("${app.finance.legacy-accruals-enabled:false}") boolean accrualsEnabled) {
        this.accrualsEnabled = accrualsEnabled;
    }

    public boolean accrualsEnabled() { return accrualsEnabled; }

    public void requireAccrualsEnabled() {
        if (!accrualsEnabled) throw new FinanceConflictException(
                "Les nouvelles commissions et remboursements de carburant sont désactivés. "
                + "Utilisez le suivi des caisses ; les anciens gains et versements restent consultables.");
    }
}
