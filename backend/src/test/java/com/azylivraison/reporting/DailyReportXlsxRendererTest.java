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
            assertThat(workbook.getSheet("Livreurs")).isNull();
            assertThat(workbook.getSheet("Clients").getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheet("Colis").getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheet("Colis").getRow(1).getCell(3).getStringCellValue())
                    .isEqualTo("Boutique Rado");
            assertThat(workbook.getSheet("Colis").getRow(2).getCell(3).getStringCellValue())
                    .isEqualTo("Boutique Rado");
        }
    }
}
