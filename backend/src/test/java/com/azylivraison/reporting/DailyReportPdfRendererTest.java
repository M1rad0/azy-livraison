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
            assertThat(text)
                    .contains("Rapport client · Maison Soa")
                    .contains("-4 000 Ar")
                    .contains("Mme Hanitra")
                    .doesNotContain("Boutique Rado")
                    .doesNotContain("Mme Volana");
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
}
