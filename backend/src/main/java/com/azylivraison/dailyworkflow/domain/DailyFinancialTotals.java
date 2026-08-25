package com.azylivraison.dailyworkflow.domain;

import java.util.List;

public record DailyFinancialTotals(
        long recoveredAriary,
        long deliveryFeesAriary,
        long amountToRemitAriary) {

    public static DailyFinancialTotals from(List<DailyPackageRecord> packages) {
        long recovered = 0;
        long fees = 0;
        for (DailyPackageRecord deliveryPackage : packages) {
            recovered = Math.addExact(recovered, deliveryPackage.recoveredAriary());
            fees = Math.addExact(fees, deliveryPackage.chargedDeliveryFeeAriary());
        }
        return new DailyFinancialTotals(recovered, fees, Math.subtractExact(recovered, fees));
    }
}
