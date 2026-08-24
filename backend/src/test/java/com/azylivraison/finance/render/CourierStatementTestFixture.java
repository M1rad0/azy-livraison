package com.azylivraison.finance.render;

import static com.azylivraison.finance.domain.FinanceModels.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryPackageOutcome;

final class CourierStatementTestFixture {

    static final UUID COURIER_ID = UUID.fromString("a1000000-0000-0000-0000-000000000001");
    static final LocalDate FROM = LocalDate.of(2026, 8, 20);
    static final LocalDate TO = LocalDate.of(2026, 8, 21);
    static final Instant GENERATED_AT = Instant.parse("2026-08-22T10:15:00Z");

    private CourierStatementTestFixture() {
    }

    static CourierFinance finance() {
        UUID firstPackage = UUID.fromString("a2000000-0000-0000-0000-000000000001");
        UUID secondPackage = UUID.fromString("a2000000-0000-0000-0000-000000000002");
        CourierDelivery delivered = new CourierDelivery(
                firstPackage, FROM, "Boutique Soa", "Rakoto",
                "Analakely", "Ivandry", DeliveryPackageOutcome.DELIVERED,
                null, 8_000, 2_667L);
        CourierDelivery returned = new CourierDelivery(
                secondPackage, TO, "Maison Rado", "Rabe",
                "Ankorondrano", "Ampasampito", DeliveryPackageOutcome.RETURN_TO_SENDER,
                Instant.parse("2026-08-21T14:00:00Z"), 5_000, 3_000L);

        CourierEarning firstEarning = new CourierEarning(
                UUID.fromString("a3000000-0000-0000-0000-000000000001"),
                FROM, 1, firstPackage, COURIER_ID, "Hery Rakoto", "034 00 000 01",
                DeliveryPackageOutcome.DELIVERED, 8_000, 1, 3, 2_667, 2_667,
                null, Instant.parse("2026-08-20T16:00:00Z"));
        CourierEarning adjustedEarning = new CourierEarning(
                UUID.fromString("a3000000-0000-0000-0000-000000000002"),
                TO, 1, secondPackage, COURIER_ID, "Hery Rakoto", "034 00 000 01",
                DeliveryPackageOutcome.RETURN_TO_SENDER, 5_000, 1, 3, 1_667, 3_000,
                "Prime exceptionnelle", Instant.parse("2026-08-21T17:00:00Z"));

        UUID adminId = UUID.fromString("a4000000-0000-0000-0000-000000000001");
        CourierPayment confirmed = new CourierPayment(
                UUID.fromString("a5000000-0000-0000-0000-000000000001"), COURIER_ID,
                2_500, "Mobile Money", new PaymentDestination("MVola", "Hery Rakoto", "0340000001"),
                "MV-2026-001", "Versement hebdomadaire",
                new ProofMetadata("proof-1", "recu-mvola.pdf", "application/pdf", 128,
                        "a".repeat(64)),
                PaymentStatus.CONFIRMED, Instant.parse("2026-08-21T18:00:00Z"), adminId,
                null, null, null, 0);
        CourierPayment voided = new CourierPayment(
                UUID.fromString("a5000000-0000-0000-0000-000000000002"), COURIER_ID,
                1_000, "Espèces", null, "CAISSE-42", "Saisie corrigée", null,
                PaymentStatus.VOIDED, Instant.parse("2026-08-20T18:00:00Z"), adminId,
                Instant.parse("2026-08-20T18:05:00Z"), adminId, "Erreur de saisie", 1);

        return new CourierFinance(
                COURIER_ID, "Hery Rakoto", 5_667, 2_500, 3_167, 0,
                List.of(returned, delivered),
                List.of(adjustedEarning, firstEarning),
                List.of(confirmed, voided));
    }
}
