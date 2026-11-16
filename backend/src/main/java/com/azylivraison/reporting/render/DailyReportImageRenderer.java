package com.azylivraison.reporting.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import com.azylivraison.dailyworkflow.service.DailyWorkflowConflictException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/** Rasterizes the existing PDF, preserving its layout rather than recreating a second template. */
@Component
public final class DailyReportImageRenderer {

    public static final float DPI = 144;
    private static final int MAX_PAGES = 200;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024 * 1024;

    public ReportDownload render(byte[] pdf, String filenameStem) {
        try (PDDocument document = Loader.loadPDF(pdf);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (document.getNumberOfPages() < 1 || document.getNumberOfPages() > MAX_PAGES) {
                throw tooLarge();
            }
            PDFRenderer renderer = new PDFRenderer(document);
            OutputStream boundedOutput = bounded(bytes);
            if (document.getNumberOfPages() == 1) {
                writePage(renderer, 0, boundedOutput);
                return new ReportDownload(bytes.toByteArray(), filenameStem + ".png", "image/png");
            }
            try (ZipOutputStream archive = new ZipOutputStream(boundedOutput)) {
                archive.setLevel(Deflater.BEST_SPEED);
                int digits = Integer.toString(document.getNumberOfPages()).length();
                for (int index = 0; index < document.getNumberOfPages(); index++) {
                    String page = String.format(Locale.ROOT, "%0" + digits + "d", index + 1);
                    archive.putNextEntry(new ZipEntry(filenameStem + "-page-" + page + ".png"));
                    writePage(renderer, index, archive);
                    archive.closeEntry();
                }
            }
            return new ReportDownload(bytes.toByteArray(), filenameStem + ".zip", "application/zip");
        } catch (IOException exception) {
            throw new IllegalStateException("Le rapport n'a pas pu être converti en images.", exception);
        }
    }

    private void writePage(PDFRenderer renderer, int pageIndex, OutputStream output) throws IOException {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, DPI, ImageType.RGB);
        try {
            if (!ImageIO.write(image, "PNG", output)) {
                throw new IOException("Le format PNG n'est pas disponible.");
            }
        } finally {
            // Release each page before rendering the next one, including multipage reports.
            image.flush();
        }
    }

    private OutputStream bounded(ByteArrayOutputStream target) {
        return new OutputStream() {
            @Override
            public void write(int value) {
                ensureCapacity(1);
                target.write(value);
            }

            @Override
            public void write(byte[] values, int offset, int length) {
                ensureCapacity(length);
                target.write(values, offset, length);
            }

            private void ensureCapacity(int additionalBytes) {
                if (additionalBytes > MAX_OUTPUT_BYTES - target.size()) throw tooLarge();
            }
        };
    }

    private DailyWorkflowConflictException tooLarge() {
        return new DailyWorkflowConflictException(
                "Ce rapport est trop volumineux pour un export photo. Téléchargez la version PDF ou Excel.");
    }
}
