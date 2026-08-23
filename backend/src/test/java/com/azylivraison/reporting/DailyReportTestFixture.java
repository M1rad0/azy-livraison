package com.azylivraison.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.reporting.domain.DailyReport;
import com.azylivraison.reporting.domain.DailyReport.ClientSummary;
import com.azylivraison.reporting.domain.DailyReport.Closure;
import com.azylivraison.reporting.domain.DailyReport.CourierSummary;
import com.azylivraison.reporting.domain.DailyReport.PackageLine;
import com.azylivraison.reporting.domain.DailyReport.PackageOutcome;
import com.azylivraison.reporting.domain.DailyReport.Totals;

final class DailyReportTestFixture {

    static final UUID CLIENT_ONE_ID = UUID.fromString("81000000-0000-0000-0000-000000000001");
    static final UUID CLIENT_TWO_ID = UUID.fromString("81000000-0000-0000-0000-000000000002");
    static final UUID COURIER_ONE_ID = UUID.fromString("82000000-0000-0000-0000-000000000001");
    static final UUID COURIER_TWO_ID = UUID.fromString("82000000-0000-0000-0000-000000000002");

    private DailyReportTestFixture() {
    }

    static DailyReport report() {
        return report(1);
    }

    static DailyReport report(int sourcePlanCount) {
        Totals general = new Totals(4, 1, 1, 1, 1, 1, 40_000, 7_000, 33_000);
        Totals clientOne = new Totals(2, 1, 1, 0, 0, 0, 40_000, 3_000, 37_000);
        Totals clientTwo = new Totals(2, 0, 0, 1, 1, 1, 0, 4_000, -4_000);
        return new DailyReport(
                UUID.fromString("83000000-0000-0000-0000-000000000001"),
                LocalDate.of(2026, 8, 24),
                UUID.fromString("84000000-0000-0000-0000-000000000001"),
                "Siège Antsahavola",
                sourcePlanCount,
                Instant.parse("2026-08-24T18:30:00Z"),
                new Closure(
                        Instant.parse("2026-08-24T17:45:00Z"),
                        UUID.fromString("85000000-0000-0000-0000-000000000001"),
                        "Administrateur AZY",
                        2),
                general,
                List.of(
                        new ClientSummary(CLIENT_ONE_ID, "Boutique Rado", "rado", "034 00 000 01", clientOne),
                        new ClientSummary(CLIENT_TWO_ID, "Maison Soa", "soa", "032 00 000 02", clientTwo)),
                List.of(
                        new CourierSummary(COURIER_ONE_ID, "Hery", "033 00 000 03", "Moto",
                                new Totals(1, 1, 0, 0, 0, 0, 40_000, 3_000, 37_000)),
                        new CourierSummary(COURIER_TWO_ID, "Fara", "034 00 000 04", "Bicyclette",
                                new Totals(1, 0, 0, 0, 1, 1, 0, 4_000, -4_000))),
                List.of(deliveredMobileMoney(), cancelled(), postponed(), returned()));
    }

    private static PackageLine deliveredMobileMoney() {
        return new PackageLine(
                UUID.fromString("86000000-0000-0000-0000-000000000001"),
                UUID.fromString("87000000-0000-0000-0000-000000000001"),
                1,
                CLIENT_ONE_ID,
                "Boutique Rado",
                "Mme Volana",
                "034 10 000 01",
                "Retrait Analakely",
                "Ivandry",
                "Centre-ville",
                PackageOutcome.DELIVERED,
                COURIER_ONE_ID,
                "Hery",
                45_000,
                40_000,
                3_000,
                37_000,
                true,
                false,
                Instant.parse("2026-08-24T08:15:00Z"),
                "Montant ajusté avec le client.",
                false,
                null,
                null,
                null,
                null);
    }

    private static PackageLine cancelled() {
        return new PackageLine(
                UUID.fromString("86000000-0000-0000-0000-000000000002"),
                UUID.fromString("87000000-0000-0000-0000-000000000001"),
                2,
                CLIENT_ONE_ID,
                "Boutique Rado",
                "M. Tiana",
                "034 10 000 02",
                "Retrait Analakely",
                "Ankorondrano",
                "Périphérie",
                PackageOutcome.CANCELLED,
                null,
                null,
                12_000,
                0,
                0,
                0,
                false,
                false,
                Instant.parse("2026-08-24T05:20:00Z"),
                "Annulé avant le dispatch.",
                false,
                null,
                null,
                null,
                null);
    }

    private static PackageLine postponed() {
        return new PackageLine(
                UUID.fromString("86000000-0000-0000-0000-000000000003"),
                UUID.fromString("87000000-0000-0000-0000-000000000002"),
                1,
                CLIENT_TWO_ID,
                "Maison Soa",
                "Mme Hanitra",
                "032 10 000 03",
                "Retrait Ampasapito",
                "Alasora",
                "Périphérie+",
                PackageOutcome.POSTPONED,
                COURIER_TWO_ID,
                "Fara",
                20_000,
                0,
                0,
                0,
                false,
                true,
                Instant.parse("2026-08-24T16:10:00Z"),
                "Destinataire indisponible.",
                false,
                null,
                UUID.fromString("86000000-0000-0000-0000-000000000013"),
                LocalDate.of(2026, 8, 25),
                "Dépôt d'Ivandry");
    }

    private static PackageLine returned() {
        return new PackageLine(
                UUID.fromString("86000000-0000-0000-0000-000000000004"),
                UUID.fromString("87000000-0000-0000-0000-000000000002"),
                2,
                CLIENT_TWO_ID,
                "Maison Soa",
                "M. Naina",
                "032 10 000 04",
                "Retrait Ampasapito",
                "Ambohimangakely",
                "Périphérie",
                PackageOutcome.RETURN_TO_SENDER,
                COURIER_TWO_ID,
                "Fara",
                15_000,
                0,
                4_000,
                -4_000,
                false,
                false,
                Instant.parse("2026-08-24T15:30:00Z"),
                "Commande refusée à l'arrivée.",
                true,
                Instant.parse("2026-08-24T17:20:00Z"),
                null,
                null,
                null);
    }
}
