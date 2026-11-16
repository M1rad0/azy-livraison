package com.azylivraison.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import com.azylivraison.dailyworkflow.service.DailyWorkflowConflictException;
import com.azylivraison.reporting.render.DailyReportImageRenderer;
import com.azylivraison.reporting.render.DailyReportPdfRenderer;
import com.azylivraison.reporting.render.ReportDownload;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

class DailyReportImageRendererTest {

    private final DailyReportImageRenderer renderer = new DailyReportImageRenderer();
    private final DailyReportPdfRenderer pdfRenderer = new DailyReportPdfRenderer();

    @Test
    void singlePagePhotoIsPixelIdenticalToTheExistingClientPdf() throws Exception {
        byte[] pdf = pdfRenderer.renderForClient(
                DailyReportTestFixture.report(), DailyReportTestFixture.CLIENT_ONE_ID);
        ReportDownload image = renderer.render(pdf, "rado-2026-08-24");

        assertThat(image.filename()).isEqualTo("rado-2026-08-24.png");
        assertThat(image.mediaType()).isEqualTo("image/png");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            BufferedImage expected = new PDFRenderer(document)
                    .renderImageWithDPI(0, DailyReportImageRenderer.DPI, ImageType.RGB);
            BufferedImage actual = ImageIO.read(new ByteArrayInputStream(image.content()));
            assertSameImage(actual, expected);
        }
    }

    @Test
    void multiplePagesAreZippedInOrderWithoutChangingThePdfPages() throws Exception {
        byte[] pdf = pdfRenderer.renderForClient(
                DailyReportTestFixture.clientOneVolumeReport(25), DailyReportTestFixture.CLIENT_ONE_ID);
        ReportDownload archive = renderer.render(pdf, "rado-2026-08-24");

        assertThat(archive.filename()).isEqualTo("rado-2026-08-24.zip");
        assertThat(archive.mediaType()).isEqualTo("application/zip");
        try (PDDocument document = Loader.loadPDF(pdf);
                ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.content()))) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            PDFRenderer reference = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                var entry = zip.getNextEntry();
                assertThat(entry).isNotNull();
                assertThat(entry.getName()).isEqualTo("rado-2026-08-24-page-" + (page + 1) + ".png");
                BufferedImage actual = ImageIO.read(new ByteArrayInputStream(zip.readAllBytes()));
                BufferedImage expected = reference.renderImageWithDPI(
                        page, DailyReportImageRenderer.DPI, ImageType.RGB);
                assertSameImage(actual, expected);
                zip.closeEntry();
            }
            assertThat(zip.getNextEntry()).isNull();
        }
    }

    @Test
    void refusesExcessivePageCountsBeforeAllocatingImages() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int index = 0; index < 201; index++) document.addPage(new PDPage());
            document.save(bytes);
            pdf = bytes.toByteArray();
        }
        assertThatThrownBy(() -> renderer.render(pdf, "rapport-2026-08-24"))
                .isInstanceOf(DailyWorkflowConflictException.class)
                .hasMessageContaining("PDF ou Excel");
    }

    private void assertSameImage(BufferedImage actual, BufferedImage expected) {
        assertThat(actual).isNotNull();
        assertThat(actual.getWidth()).isEqualTo(expected.getWidth());
        assertThat(actual.getHeight()).isEqualTo(expected.getHeight());
        assertArrayEquals(
                expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()),
                actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()),
                "Chaque pixel de la photo doit correspondre au PDF.");
        actual.flush();
        expected.flush();
    }
}
