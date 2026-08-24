package com.azylivraison.finance.render;

import static com.azylivraison.finance.render.CourierStatementTestFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class CourierStatementRenderersTest {

    @Test
    void pdfContainsIdentityPeriodTotalsAndEveryFinancialSection() throws Exception {
        byte[] content = new CourierStatementPdfRenderer().render(finance(), FROM, TO, GENERATED_AT);

        assertThat(new String(content, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(content)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("Relevé financier livreur")
                    .contains("Hery Rakoto")
                    .contains(COURIER_ID.toString())
                    .contains("Du 20/08/2026 au 21/08/2026")
                    .contains("Total gagné")
                    .contains("5 667 Ar")
                    .contains("Total versé")
                    .contains("2 500 Ar")
                    .contains("Reste à verser")
                    .contains("3 167 Ar")
                    .contains("Boutique Soa")
                    .contains("Rakoto")
                    .contains("Prime exceptionnelle")
                    .contains("Fraction figée : 1/3")
                    .contains("recu-mvola.pdf")
                    .contains("ANNULÉ")
                    .contains("Erreur de saisie");
        }
    }

    @Test
    void workbookKeepsAmountsNumericAndVoidedPaymentsVisible() throws Exception {
        byte[] content = new CourierStatementXlsxRenderer().render(finance(), FROM, TO, GENERATED_AT);

        assertThat(content[0]).isEqualTo((byte) 'P');
        assertThat(content[1]).isEqualTo((byte) 'K');
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertThat(workbook.getSheet("Synthèse")).isNotNull();
            assertThat(workbook.getSheet("Livraisons")).isNotNull();
            assertThat(workbook.getSheet("Rémunérations")).isNotNull();
            assertThat(workbook.getSheet("Versements")).isNotNull();

            Sheet summary = workbook.getSheet("Synthèse");
            assertThat(valueFor(summary, "Livreur")).isEqualTo("Hery Rakoto");
            assertThat(valueFor(summary, "Période détaillée")).isEqualTo("Du 20/08/2026 au 21/08/2026");
            assertThat(numberFor(summary, "Total gagné")).isEqualTo(5_667);
            assertThat(numberFor(summary, "Total versé")).isEqualTo(2_500);
            assertThat(numberFor(summary, "Reste à verser")).isEqualTo(3_167);
            assertThat(numberFor(summary, "Avance ou crédit")).isZero();

            Row delivery = rowWithValue(workbook.getSheet("Livraisons"), 2, "Rakoto");
            assertThat(delivery.getCell(6).getNumericCellValue()).isEqualTo(8_000);
            assertThat(delivery.getCell(7).getNumericCellValue()).isEqualTo(2_667);

            Row earning = rowWithValue(workbook.getSheet("Rémunérations"), 8, "Prime exceptionnelle");
            assertThat(earning.getCell(3).getNumericCellValue()).isEqualTo(1);
            assertThat(earning.getCell(4).getNumericCellValue()).isEqualTo(3);
            assertThat(earning.getCell(7).getNumericCellValue()).isEqualTo(3_000);

            Row voided = rowWithValue(workbook.getSheet("Versements"), 2, "ANNULÉ");
            assertThat(voided.getCell(1).getNumericCellValue()).isEqualTo(1_000);
            assertThat(voided.getCell(11).getStringCellValue()).isEqualTo("Erreur de saisie");
            assertThat(sheetContains(workbook.getSheet("Versements"), "recu-mvola.pdf")).isTrue();
        }
    }

    private String valueFor(Sheet sheet, String key) {
        for (Row row : sheet) {
            if (row.getCell(0) != null && key.equals(row.getCell(0).getStringCellValue())) {
                return row.getCell(1).getStringCellValue();
            }
        }
        throw new AssertionError("Clé absente : " + key);
    }

    private long numberFor(Sheet sheet, String key) {
        for (Row row : sheet) {
            if (row.getCell(0) != null && key.equals(row.getCell(0).getStringCellValue())) {
                return Math.round(row.getCell(1).getNumericCellValue());
            }
        }
        throw new AssertionError("Clé absente : " + key);
    }

    private Row rowWithValue(Sheet sheet, int column, String value) {
        for (Row row : sheet) {
            if (row.getCell(column) != null && value.equals(row.getCell(column).getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("Valeur absente : " + value);
    }

    private boolean sheetContains(Sheet sheet, String value) {
        for (Row row : sheet) {
            for (org.apache.poi.ss.usermodel.Cell cell : row) {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && cell.getStringCellValue().contains(value)) return true;
            }
        }
        return false;
    }
}
