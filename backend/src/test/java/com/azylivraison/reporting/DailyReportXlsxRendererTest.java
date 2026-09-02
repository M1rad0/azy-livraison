package com.azylivraison.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;

import com.azylivraison.reporting.render.DailyReportXlsxRenderer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DailyReportXlsxRendererTest {

    private final DailyReportXlsxRenderer renderer = new DailyReportXlsxRenderer();

    @Test
    void rendersARealWorkbookWithAllGeneralReportSheets() throws Exception {
        byte[] result = renderer.render(DailyReportTestFixture.report());

        assertThat(result[0]).isEqualTo((byte) 'P');
        assertThat(result[1]).isEqualTo((byte) 'K');
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(5);
            assertThat(workbook.getSheetName(0)).isEqualTo("Synthèse");
            assertThat(workbook.getSheet("Clients")).isNotNull();
            assertThat(workbook.getSheet("Livreurs")).isNotNull();
            assertThat(workbook.getSheet("Colis")).isNotNull();
            assertThat(workbook.getSheet("Retours et reports")).isNotNull();

            Sheet packages = workbook.getSheet("Colis");
            Row deliveredMobileMoney = packages.getRow(1);
            assertThat(deliveredMobileMoney.getCell(4).getStringCellValue()).isEqualTo("Mme Volana");
            assertThat(deliveredMobileMoney.getCell(10).getStringCellValue()).isEqualTo("Hery");
            assertThat(deliveredMobileMoney.getCell(11).getStringCellValue()).isEqualTo("Fara");
            assertThat(deliveredMobileMoney.getCell(13).getNumericCellValue()).isEqualTo(40_000);
            assertThat(deliveredMobileMoney.getCell(14).getNumericCellValue()).isEqualTo(3_000);
            assertThat(deliveredMobileMoney.getCell(15).getNumericCellValue()).isEqualTo(37_000);
            assertThat(deliveredMobileMoney.getCell(16).getStringCellValue()).isEqualTo("Oui");

            Row returned = packages.getRow(4);
            assertThat(returned.getCell(10).getStringCellValue()).isEqualTo("Fara");
            assertThat(returned.getCell(11).getStringCellValue()).isEqualTo("Hery");
            assertThat(returned.getCell(15).getNumericCellValue()).isEqualTo(-4_000);
            assertThat(returned.getCell(19).getStringCellValue()).isEqualTo("Retourné à l'expéditeur");

            Sheet couriers = workbook.getSheet("Livreurs");
            assertThat(couriers.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Hery");
            assertThat(couriers.getRow(1).getCell(4).getNumericCellValue()).isZero();
            assertThat(couriers.getRow(1).getCell(7).getNumericCellValue()).isEqualTo(1);
            assertThat(couriers.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Fara");
            assertThat(couriers.getRow(2).getCell(4).getNumericCellValue()).isEqualTo(1);
        }
    }

    @Test
    void rendersAClientWorkbookLimitedToItsOwnPackages() throws Exception {
        byte[] result = renderer.renderForClient(
                DailyReportTestFixture.report(), DailyReportTestFixture.CLIENT_ONE_ID);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            Sheet report = workbook.getSheet("Rapport client");
            assertThat(report).isNotNull();
            assertThat(workbook.getSheet("Livreurs")).isNull();
            assertThat(workbook.getSheet("Clients")).isNull();
            assertThat(workbook.getSheet("Colis")).isNull();
            assertThat(workbook.getSheet("Retours et reports")).isNull();

            Row header = rowWithValue(report, 0, "Destinataire");
            Row delivered = report.getRow(header.getRowNum() + 1);
            assertThat(delivered.getCell(0).getStringCellValue()).isEqualTo("Mme Volana");
            assertThat(delivered.getCell(1).getStringCellValue()).isEqualTo("034 10 000 01");
            assertThat(delivered.getCell(2).getStringCellValue()).isEqualTo("Ivandry");
            assertThat(delivered.getCell(3).getStringCellValue()).isEqualTo("Livré");
            assertThat(delivered.getCell(4).getStringCellValue()).isEqualTo("Montant ajusté avec le client.");
            assertThat(delivered.getCell(5).getNumericCellValue()).isEqualTo(40_000);
            assertThat(delivered.getCell(6).getNumericCellValue()).isEqualTo(3_000);
            assertThat(delivered.getCell(7).getNumericCellValue()).isEqualTo(37_000);
            assertThat(rowWithValue(report, 0, "Synthèse").getRowNum()).isLessThan(header.getRowNum());
            Row summary = rowWithValue(report, 0, "Colis");
            assertThat(summary.getCell(1).getNumericCellValue()).isEqualTo(2);
            assertThat(summary.getCell(2).getStringCellValue()).isEqualTo("Total récupéré");
            assertThat(summary.getCell(3).getNumericCellValue()).isEqualTo(40_000);
            assertThat(summary.getCell(4).getStringCellValue()).isEqualTo("Frais de livraison");
            assertThat(summary.getCell(5).getNumericCellValue()).isEqualTo(3_000);
            assertThat(summary.getCell(6).getStringCellValue()).isEqualTo("Total à reverser");
            assertThat(summary.getCell(7).getNumericCellValue()).isEqualTo(37_000);

            assertThat(stringOccurrences(report, "Retrait Analakely")).isEqualTo(1);
            assertThat(sheetContains(report, "Boutique Rado")).isTrue();
            assertThat(sheetContains(report, "24 août 2026")).isTrue();
            assertThat(report.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Boutique Rado");
            assertThat(report.getRow(1).getCell(5).getStringCellValue()).isEqualTo("24 août 2026");
            assertThat(workbook.getFontAt(report.getRow(1).getCell(0).getCellStyle().getFontIndex())
                    .getFontHeightInPoints())
                    .isGreaterThanOrEqualTo((short) 16);
            assertThat(report.getRow(1).getCell(5).getCellStyle().getAlignment())
                    .isEqualTo(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
            assertThat(sheetContains(report, "Hery")).isFalse();
            assertThat(sheetContains(report, "Fara")).isFalse();
            assertThat(sheetContains(report, "Clôturée le")).isFalse();
            assertThat(sheetContains(report, "Généré le")).isFalse();
            assertThat(sheetContains(report, "Plan")).isFalse();
            assertThat(sheetContains(report, "Point de ralliement")).isFalse();
            assertThat(sheetContains(report, "Montant prévu (Ar)")).isFalse();
        }
    }

    @Test
    void storesManyDistinctPickupsOnSeparateRows() throws Exception {
        byte[] result = renderer.renderForClient(
                DailyReportTestFixture.clientOneVolumeReport(60), DailyReportTestFixture.CLIENT_ONE_ID);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Sheet report = workbook.getSheet("Rapport client");
            assertThat(stringOccurrences(report, "Points de récupération")).isEqualTo(1);
            assertThat(stringOccurrences(report, "Retrait unique 1 — Antananarivo")).isEqualTo(1);
            assertThat(stringOccurrences(report, "Retrait unique 60 — Antananarivo")).isEqualTo(1);
            Row header = rowWithValue(report, 0, "Destinataire");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Commentaire");
            assertThat(rowWithValue(report, 0, "Synthèse").getRowNum()).isLessThan(header.getRowNum());
            assertThat(report.getPaneInformation()).isNull();
        }
    }

    @Test
    void storesTheFullClientCommentInTheTable() throws Exception {
        String longComment = "Début du commentaire. " + "Information complète à conserver. ".repeat(28)
                + "Fin intégrale du commentaire.";
        byte[] result = renderer.renderForClient(
                DailyReportTestFixture.clientOneReportWithComment(longComment),
                DailyReportTestFixture.CLIENT_ONE_ID);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Sheet report = workbook.getSheet("Rapport client");
            Row delivered = rowWithValue(report, 0, "Mme Volana");
            assertThat(delivered.getCell(4).getStringCellValue()).isEqualTo(longComment);
            assertThat(delivered.getHeightInPoints()).isGreaterThan(90);
        }
    }

    private Row rowWithValue(Sheet sheet, int column, String expected) {
        for (Row row : sheet) {
            if (row.getCell(column) != null && expected.equals(row.getCell(column).getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("Ligne absente : " + expected);
    }

    private boolean sheetContains(Sheet sheet, String expected) {
        return stringOccurrences(sheet, expected) > 0;
    }

    private int stringOccurrences(Sheet sheet, String expected) {
        int count = 0;
        for (Row row : sheet) {
            for (var cell : row) {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && expected.equals(cell.getStringCellValue())) count++;
            }
        }
        return count;
    }
}
