package com.azylivraison.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.azylivraison.reporting.domain.DailyReport;
import com.azylivraison.reporting.domain.DailyReport.ClientSummary;
import com.azylivraison.reporting.render.DailyReportPdfRenderer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class DailyReportPdfRendererTest {

    private final DailyReportPdfRenderer renderer = new DailyReportPdfRenderer();

    @Test
    void rendersAReadableGeneralPdfWithFinancialAndOperationalSections() throws Exception {
        byte[] result = renderer.render(DailyReportTestFixture.report());

        assertThat(new String(result, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(result)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isPositive();
            assertThat(text)
                    .contains("AZY LIVRAISON")
                    .contains("Rapport général")
                    .contains("Boutique Rado")
                    .contains("Maison Soa")
                    .contains("034 10 000 01")
                    .contains("Total récupéré")
                    .contains("40 000 Ar")
                    .contains("Total à reverser")
                    .contains("33 000 Ar")
                    .contains("Livreurs réels")
                    .contains("Livreur prévu : Hery")
                    .contains("Livreur réel : Fara")
                    .contains("Mobile Money (informatif)")
                    .contains("Retourné à l'expéditeur");
        }
    }

    @Test
    void rendersAClientPdfWithoutLeakingAnotherClient() throws Exception {
        byte[] result = renderer.renderForClient(
                DailyReportTestFixture.report(), DailyReportTestFixture.CLIENT_TWO_ID);

        try (PDDocument document = Loader.loadPDF(result)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getPage(0).getMediaBox().getWidth())
                    .isGreaterThan(document.getPage(0).getMediaBox().getHeight());
            assertThat(text)
                    .contains("Rapport client")
                    .contains("24 août 2026")
                    .contains("Maison Soa")
                    .contains("Point de récupération")
                    .contains("Retrait Ampasapito")
                    .contains("Destinataire")
                    .contains("Destination")
                    .contains("Récupéré")
                    .contains("Frais")
                    .contains("À reverser")
                    .contains("Synthèse")
                    .contains("-4 000 Ar")
                    .contains("Mme Hanitra")
                    .contains("Destinataire indisponible.")
                    .contains("Commande refusée à l'arrivée.")
                    .doesNotContain("Boutique Rado")
                    .doesNotContain("Mme Volana")
                    .doesNotContain("Clôture de la journée")
                    .doesNotContain("Point de ralliement")
                    .doesNotContain("Siège Antsahavola")
                    .doesNotContain("Plan")
                    .doesNotContain("Livreur")
                    .doesNotContain("Mobile Money")
                    .doesNotContain("Hery")
                    .doesNotContain("Fara")
                    .doesNotContain("Prévu")
                    .doesNotContain("Généré le")
                    .doesNotContain("17:45")
                    .doesNotContain("18:30")
                    .doesNotContain("15:30")
                    .doesNotContain("17:20");
            assertThat(occurrences(text, "Retrait Ampasapito")).isEqualTo(1);
            assertThat(text.indexOf("Synthèse")).isLessThan(text.indexOf("Livraisons"));
        }
    }

    @Test
    void rejectsAnAccountOutsideTheReport() {
        assertThatThrownBy(() -> renderer.renderForClient(DailyReportTestFixture.report(), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent du rapport");
    }

    @Test
    void identifiesConsolidatedLegacyPlansWithoutPresentingOneAsTheOnlySource() throws Exception {
        byte[] result = renderer.render(DailyReportTestFixture.report(2));

        try (PDDocument document = Loader.loadPDF(result)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("Plan de référence")
                    .contains("Historique consolidé")
                    .contains("2 plans de dispatch");
        }
    }

    @Test
    void preservesUnicodeAndWrapsLongClientHeadings() throws Exception {
        DailyReport source = DailyReportTestFixture.report();
        ClientSummary original = source.clients().getFirst();
        String longUnicodeName = "Société Łódź — Fiantsenana sy Fandefasana Iraisam-pirenena "
                + "eto Antananarivo miaraka amin’ny sampana Analakely sy Ivandry";
        ClientSummary renamed = new ClientSummary(
                original.accountId(),
                longUnicodeName,
                original.username(),
                original.phone(),
                original.totals());
        DailyReport report = new DailyReport(
                source.reportId(),
                source.serviceDate(),
                source.dispatchPlanId(),
                source.hubLabel(),
                source.sourcePlanCount(),
                source.generatedAt(),
                source.closure(),
                source.totals(),
                List.of(renamed, source.clients().get(1)),
                source.couriers(),
                source.packages());

        byte[] result = renderer.renderForClient(report, renamed.accountId());

        try (PDDocument document = Loader.loadPDF(result)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("Société Łódź")
                    .contains("Iraisam-pirenena")
                    .contains("Analakely sy Ivandry");
        }
    }

    @Test
    void paginatesManyPickupsAndKeepsTheOpeningSummaryTogether() throws Exception {
        byte[] result = renderer.renderForClient(
                DailyReportTestFixture.clientOneVolumeReport(60), DailyReportTestFixture.CLIENT_ONE_ID);

        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(3);
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("Retrait unique 1 — Antananarivo")
                    .contains("Retrait unique 60 — Antananarivo")
                    .doesNotContain("Mobile Money");
            assertThat(occurrences(text, "Retrait unique 1 — Antananarivo")).isEqualTo(1);
            assertThat(occurrences(text, "Retrait unique 60 — Antananarivo")).isEqualTo(1);

            String summaryPage = null;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDFTextStripper pageStripper = new PDFTextStripper();
                pageStripper.setStartPage(page);
                pageStripper.setEndPage(page);
                String pageText = pageStripper.getText(document);
                if (pageText.contains("Synthèse")) summaryPage = pageText;
            }
            assertThat(summaryPage)
                    .isNotNull()
                    .contains("Total récupéré")
                    .contains("Frais de livraison")
                    .contains("Total à reverser");
        }
    }

    @Test
    void keepsAFullLongClientCommentInsteadOfSilentlyTruncatingIt() throws Exception {
        String longComment = "Début du commentaire. " + "Information complète à conserver. ".repeat(28)
                + "Fin intégrale du commentaire.";
        byte[] result = renderer.renderForClient(
                DailyReportTestFixture.clientOneReportWithComment(longComment),
                DailyReportTestFixture.CLIENT_ONE_ID);

        try (PDDocument document = Loader.loadPDF(result)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("Début du commentaire.")
                    .contains("Fin intégrale du")
                    .contains("commentaire.");
        }
    }

    private int occurrences(String value, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }
}
