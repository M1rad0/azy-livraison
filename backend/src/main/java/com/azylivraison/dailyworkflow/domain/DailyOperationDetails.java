package com.azylivraison.dailyworkflow.domain;

import java.util.List;

public record DailyOperationDetails(
        OperationDay day,
        List<DailyPackageRecord> packages,
        DailyFinancialTotals totals) {

    public DailyOperationDetails {
        packages = List.copyOf(packages);
    }

    public static DailyOperationDetails of(OperationDay day, List<DailyPackageRecord> packages) {
        return new DailyOperationDetails(day, packages, DailyFinancialTotals.from(packages));
    }
}
