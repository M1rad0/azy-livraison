package com.azylivraison.reporting.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.awt.Color;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.azylivraison.reporting.domain.DailyReport;
import com.azylivraison.reporting.domain.DailyReport.ClientSummary;
import com.azylivraison.reporting.domain.DailyReport.CourierSummary;
import com.azylivraison.reporting.domain.DailyReport.PackageLine;
import com.azylivraison.reporting.domain.DailyReport.PackageOutcome;
import com.azylivraison.reporting.domain.DailyReport.Totals;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

/** Stateless PDF renderer. It never reads the database and never recalculates totals. */
@Component
public final class DailyReportPdfRenderer {

    private static final ZoneId ANTANANARIVO = ZoneId.of("Indian/Antananarivo");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE)
            .withZone(ANTANANARIVO);
    private static final NumberFormat INTEGER = NumberFormat.getIntegerInstance(Locale.FRANCE);

    private static final int NAVY_RED = 11;
    private static final int NAVY_GREEN = 53;
    private static final int NAVY_BLUE = 87;
    private static final int ORANGE_RED = 244;
    private static final int ORANGE_GREEN = 91;
    private static final int ORANGE_BLUE = 11;

    public byte[] render(DailyReport report) {
        Objects.requireNonNull(report, "report");
        return renderView(report, "Rapport général", report.totals(), report.clients(), report.couriers(),
                report.packages());
    }

    public byte[] renderForClient(DailyReport report, UUID accountId) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(accountId, "accountId");
        ClientSummary client = report.clients().stream()
                .filter(item -> item.accountId().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Le compte client est absent du rapport."));
        List<PackageLine> packages = report.packages().stream()
                .filter(item -> item.ownerAccountId().equals(accountId))
                .toList();
        return renderClientView(report, client, packages);
    }

    private byte[] renderClientView(
            DailyReport report,
            ClientSummary client,
            List<PackageLine> packages) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(document, report, "Rapport client", true);
            writer.openPage();
            writer.line("Client", text(client.displayName()));
            if (client.phone() != null && !client.phone().isBlank()) {
                writer.line("Contact", client.phone().strip());
            }

            List<String> pickups = pickupLabels(packages);
            writer.lines(pickups.size() > 1 ? "Points de récupération" : "Point de récupération", pickups);
            writer.gap(8);

            writer.section("Livraisons");
            String[] headers = {
                "Destinataire", "Contact", "Destination", "État", "Mobile Money",
                "Récupéré", "Frais", "À reverser"
            };
            float[] widths = {118, 82, 160, 92, 88, 78, 62, 78};
            writer.tableHeader(headers, widths);
            for (PackageLine item : packages) {
                writer.tableRow(new String[] {
                    text(item.recipientName()),
                    text(item.recipientContact()),
                    text(item.destinationLabel()),
                    clientOutcomeLabel(item),
                    item.mobileMoneyPaid() ? "Oui" : "Non",
                    ariary(item.actualAmountCollectedAriary()),
                    ariary(item.appliedDeliveryFeeAriary()),
                    ariary(item.amountToRemitAriary())
                }, widths);
            }
            writer.gap(10);
            writeTotals(writer, client.totals());
            writer.close();
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Le rapport PDF client n'a pas pu être généré.", exception);
        }
    }

    private byte[] renderView(
            DailyReport report,
            String title,
            Totals totals,
            List<ClientSummary> clients,
            List<CourierSummary> couriers,
            List<PackageLine> packages) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(document, report, title);
            writer.openPage();
            writeClosure(writer, report);
            writeTotals(writer, totals);
            writeClients(writer, clients);
            writeCouriers(writer, couriers);
            writePackages(writer, packages);
            writer.close();
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Le rapport PDF n'a pas pu être généré.", exception);
        }
    }

    private void writeClosure(PdfWriter writer, DailyReport report) throws IOException {
        writer.section("Clôture de la journée");
        writer.line("Date d'activité", DATE.format(report.serviceDate()));
        writer.line("Clôturée le", formatDateTime(report.closure().closedAt()));
        writer.line("Clôturée par", text(report.closure().closedByDisplayName()));
        writer.line("Version de clôture", Integer.toString(report.closure().version()));
        writer.line(report.sourcePlanCount() > 1 ? "Point de ralliement de référence" : "Point de ralliement",
                text(report.hubLabel()));
        writer.line(report.sourcePlanCount() > 1 ? "Plan de référence" : "Plan",
                report.dispatchPlanId().toString());
        if (report.sourcePlanCount() > 1) {
            writer.line("Historique consolidé", report.sourcePlanCount() + " plans de dispatch");
        }
        writer.gap(5);
    }

    private void writeTotals(PdfWriter writer, Totals totals) throws IOException {
        writer.keepTogether(110);
        writer.section("Synthèse");
        writer.emphasis("Total récupéré", ariary(totals.amountCollectedAriary()));
        writer.emphasis("Frais de livraison", ariary(totals.deliveryFeesAriary()));
        writer.emphasis("Total à reverser", ariary(totals.amountToRemitAriary()));
        writer.paragraph(String.format(Locale.ROOT,
                "%d colis · %d livrés · %d annulés · %d reportés · %d à retourner · %d déjà retournés",
                totals.packageCount(), totals.deliveredCount(), totals.cancelledCount(), totals.postponedCount(),
                totals.returnRequiredCount(), totals.returnedToSenderCount()));
        writer.gap(5);
    }

    private void writeClients(PdfWriter writer, List<ClientSummary> clients) throws IOException {
        if (clients.isEmpty()) return;
        writer.section("Comptes clients");
        for (ClientSummary client : clients) {
            String contact = joinNonBlank(" · ", client.username(), client.phone());
            writer.row(text(client.displayName()), contact,
                    client.totals().packageCount() + " colis · "
                            + ariary(client.totals().amountCollectedAriary()) + " récupérés · "
                            + ariary(client.totals().deliveryFeesAriary()) + " de frais · "
                            + ariary(client.totals().amountToRemitAriary()) + " à reverser");
        }
        writer.gap(5);
    }

    private void writeCouriers(PdfWriter writer, List<CourierSummary> couriers) throws IOException {
        if (couriers.isEmpty()) return;
        writer.section("Livreurs réels");
        for (CourierSummary courier : couriers) {
            String contact = joinNonBlank(" · ", courier.vehicleType(), courier.contact());
            writer.row(text(courier.displayName()), contact,
                    courier.totals().deliveredCount() + " livrés · "
                            + courier.totals().returnRequiredCount() + " à retourner · "
                            + ariary(courier.totals().amountCollectedAriary()) + " récupérés");
        }
        writer.gap(5);
    }

    private void writePackages(PdfWriter writer, List<PackageLine> packages) throws IOException {
        writer.section("Détail des colis");
        if (packages.isEmpty()) {
            writer.paragraph("Aucun colis dans ce rapport.");
            return;
        }
        for (PackageLine item : packages) {
            String title = "#" + item.sequenceNumber() + " · " + text(item.recipientName())
                    + " · " + item.outcome().frenchLabel();
            String route = text(item.pickupLabel()) + " → " + text(item.destinationLabel());
            String assignment = joinNonBlank(" · ", text(item.clientDisplayName()),
                    item.recipientContact() == null ? null : "Contact : " + item.recipientContact(),
                    item.plannedCourierName() == null ? null : "Livreur prévu : " + item.plannedCourierName(),
                    actualCourierLabel(item));
            String finance = "Prévu " + ariary(item.expectedAmountToCollectAriary())
                    + " · Récupéré " + ariary(item.actualAmountCollectedAriary())
                    + " · Frais " + ariary(item.appliedDeliveryFeeAriary())
                    + " · À reverser " + ariary(item.amountToRemitAriary());
            List<String> flags = new ArrayList<>();
            if (item.mobileMoneyPaid()) flags.add("Mobile Money (informatif)");
            if (item.faritra()) flags.add("Faritra");
            if (item.pricingZoneName() != null && !item.pricingZoneName().isBlank()) {
                flags.add("Zone " + item.pricingZoneName());
            }
            if (item.outcome() == PackageOutcome.RETURN_TO_SENDER) {
                flags.add(item.returnedToSender() ? "Retourné à l'expéditeur" : "Retour en attente");
                if (item.returnedToSenderAt() != null) {
                    flags.add("retour le " + formatDateTime(item.returnedToSenderAt()));
                }
            }
            if (item.outcome() == PackageOutcome.POSTPONED && item.postponedToDate() != null) {
                flags.add("reporté au " + DATE.format(item.postponedToDate()));
                if (item.replacementPickupLabel() != null) {
                    flags.add("nouveau retrait : " + item.replacementPickupLabel());
                }
            }
            if (item.finalizedAt() != null) flags.add("finalisé le " + formatDateTime(item.finalizedAt()));
            writer.packageRow(title, route, assignment, finance, joinNonBlank(" · ", flags.toArray(String[]::new)),
                    item.outcomeNote());
        }
    }

    private static String ariary(long value) {
        synchronized (INTEGER) {
            return INTEGER.format(value) + " Ar";
        }
    }

    private static String actualCourierLabel(PackageLine item) {
        if (item.actualCourierName() != null && !item.actualCourierName().isBlank()) {
            return "Livreur réel : " + item.actualCourierName();
        }
        return item.outcome() == PackageOutcome.DELIVERED
                || item.outcome() == PackageOutcome.RETURN_TO_SENDER
                ? "Livreur réel : Non renseigné (historique)"
                : null;
    }

    private static String clientOutcomeLabel(PackageLine item) {
        if (item.outcome() == PackageOutcome.RETURN_TO_SENDER && item.returnedToSender()) {
            return "Retourné à l'expéditeur";
        }
        if (item.outcome() == PackageOutcome.POSTPONED && item.postponedToDate() != null) {
            return "Reporté au " + DATE.format(item.postponedToDate());
        }
        return item.outcome().frenchLabel();
    }

    private static List<String> pickupLabels(List<PackageLine> packages) {
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (PackageLine item : packages) {
            if (item.pickupLabel() == null || item.pickupLabel().isBlank()) continue;
            String label = item.pickupLabel().strip();
            unique.putIfAbsent(label.toLowerCase(Locale.ROOT), label);
        }
        return List.copyOf(unique.values());
    }

    private static String formatDateTime(Instant instant) {
        return instant == null ? "—" : DATE_TIME.format(instant);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "—" : value.strip();
    }

    private static String joinNonBlank(String delimiter, String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !"—".equals(value)) present.add(value.strip());
        }
        return present.isEmpty() ? "—" : String.join(delimiter, present);
    }

    private static final class PdfWriter implements AutoCloseable {

        private static final float MARGIN = 42;
        private static final float FOOTER_HEIGHT = 28;
        private static final String FONT_RESOURCE = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

        private final PDDocument document;
        private final DailyReport report;
        private final String title;
        private final boolean clientView;
        private final PDRectangle pageSize;
        private final float top;
        private final float width;
        private final PDFont regular;
        private final PDFont bold;
        private PDPageContentStream content;
        private float y;
        private int pageNumber;
        private String[] activeTableHeaders;
        private float[] activeTableWidths;

        private PdfWriter(PDDocument document, DailyReport report, String title) throws IOException {
            this(document, report, title, false);
        }

        private PdfWriter(PDDocument document, DailyReport report, String title, boolean clientView)
                throws IOException {
            this.document = document;
            this.report = report;
            this.title = title;
            this.clientView = clientView;
            this.pageSize = clientView
                    ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())
                    : PDRectangle.A4;
            this.top = pageSize.getHeight() - MARGIN;
            this.width = pageSize.getWidth() - (2 * MARGIN);
            try (InputStream fontStream = DailyReportPdfRenderer.class.getResourceAsStream(FONT_RESOURCE)) {
                if (fontStream == null) {
                    throw new IOException("La police Unicode du rapport est introuvable.");
                }
                regular = PDType0Font.load(document, fontStream, true);
                bold = regular;
            }
        }

        void openPage() throws IOException {
            if (content != null) content.close();
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            pageNumber++;

            content.setNonStrokingColor(new Color(NAVY_RED, NAVY_GREEN, NAVY_BLUE));
            content.addRect(0, pageSize.getHeight() - 112, pageSize.getWidth(), 112);
            content.fill();
            content.setNonStrokingColor(new Color(ORANGE_RED, ORANGE_GREEN, ORANGE_BLUE));
            content.addRect(0, pageSize.getHeight() - 117, pageSize.getWidth(), 5);
            content.fill();
            drawText("AZY LIVRAISON", MARGIN, top - 13, bold, 17, 255, 255, 255);
            drawWrappedAt(safe(title), MARGIN, top - 36, width, regular, clientView ? 13 : 11,
                    clientView ? 14 : 12, 255, 255, 255);
            drawText("Journée du " + DATE.format(report.serviceDate()), MARGIN, top - 66,
                    regular, 9, 220, 230, 238);
            String footer = clientView
                    ? "Page " + pageNumber
                    : "Page " + pageNumber + " · Généré le " + formatDateTime(report.generatedAt());
            drawText(footer, MARGIN, 17, regular, 7.5f, 90, 104, 116);
            y = pageSize.getHeight() - 137;
        }

        void section(String value) throws IOException {
            ensure(28);
            content.setNonStrokingColor(new Color(ORANGE_RED, ORANGE_GREEN, ORANGE_BLUE));
            content.addRect(MARGIN, y - 3, 4, 17);
            content.fill();
            drawText(safe(value), MARGIN + 10, y, bold, 12, NAVY_RED, NAVY_GREEN, NAVY_BLUE);
            y -= 23;
        }

        void line(String label, String value) throws IOException {
            ensure(16);
            float fontSize = clientView ? 10 : 8.5f;
            float leading = clientView ? 13 : 11;
            float labelWidth = clientView ? 122 : 112;
            drawText(safe(label), MARGIN, y, bold, fontSize, 45, 58, 68);
            writeWrapped(safe(value), MARGIN + labelWidth, width - labelWidth,
                    regular, fontSize, leading, 45, 58, 68);
            y -= 3;
        }

        void lines(String label, List<String> values) throws IOException {
            if (values.isEmpty()) {
                line(label, "—");
                return;
            }
            ensure(29);
            float fontSize = clientView ? 10 : 8.5f;
            float leading = clientView ? 13 : 11;
            float labelWidth = clientView ? 122 : 112;
            drawText(safe(label), MARGIN, y, bold, fontSize, 45, 58, 68);
            float valueX = MARGIN + labelWidth;
            float valueWidth = width - labelWidth;
            for (String value : values) {
                writeWrapped("• " + safe(value), valueX, valueWidth,
                        regular, fontSize, leading, 45, 58, 68);
            }
            y -= 3;
        }

        void keepTogether(float required) throws IOException {
            ensure(required);
        }

        void emphasis(String label, String value) throws IOException {
            ensure(22);
            drawText(safe(label), MARGIN, y, regular, 9, 70, 82, 92);
            drawText(safe(value), MARGIN + 190, y, bold, 11, NAVY_RED, NAVY_GREEN, NAVY_BLUE);
            y -= 18;
        }

        void paragraph(String value) throws IOException {
            ensure(20);
            writeWrapped(safe(value), MARGIN, width, regular, 8.5f, 11, 55, 68, 78);
        }

        void row(String heading, String metadata, String details) throws IOException {
            ensure(50);
            writeWrapped(safe(heading), MARGIN + 8, width - 16, bold, 9.5f, 12,
                    NAVY_RED, NAVY_GREEN, NAVY_BLUE);
            if (metadata != null && !metadata.isBlank() && !"—".equals(metadata)) {
                writeWrapped(safe(metadata), MARGIN + 8, width - 16, regular, 7.8f, 10, 88, 100, 110);
            }
            writeWrapped(safe(details), MARGIN + 8, width - 16, regular, 8.2f, 10.5f, 45, 58, 68);
            separator();
        }

        void tableHeader(String[] headers, float[] widths) throws IOException {
            if (headers.length != widths.length) throw new IllegalArgumentException("Colonnes de tableau invalides.");
            float totalWidth = 0;
            for (float columnWidth : widths) totalWidth += columnWidth;
            if (Math.abs(totalWidth - width) > 1f) {
                throw new IllegalArgumentException("La largeur du tableau ne correspond pas à la page.");
            }
            activeTableHeaders = headers.clone();
            activeTableWidths = widths.clone();
            drawTableRow(activeTableHeaders, activeTableWidths, true);
        }

        void tableRow(String[] values, float[] widths) throws IOException {
            if (values.length != widths.length) throw new IllegalArgumentException("Colonnes de tableau invalides.");
            float required = tableRowHeight(values, widths, false);
            if (y - required < FOOTER_HEIGHT + 12) {
                openPage();
                if (activeTableHeaders != null && activeTableWidths != null) {
                    drawTableRow(activeTableHeaders, activeTableWidths, true);
                }
            }
            drawTableRow(values, widths, false);
        }

        private void drawTableRow(String[] values, float[] widths, boolean header) throws IOException {
            PDFont font = header ? bold : regular;
            float fontSize = header ? 10 : 9.5f;
            float leading = header ? 12.5f : 12.2f;
            List<List<String>> wrapped = new ArrayList<>();
            int maxLines = 1;
            for (int index = 0; index < values.length; index++) {
                List<String> lines = wrap(safe(values[index]), font, fontSize, widths[index] - 10);
                wrapped.add(lines);
                maxLines = Math.max(maxLines, lines.size());
            }
            float rowHeight = Math.max(header ? 29 : 31, (maxLines * leading) + 13);
            if (header && y - rowHeight < FOOTER_HEIGHT + 12) openPage();

            float rowTop = y;
            float rowBottom = rowTop - rowHeight;
            if (header) {
                content.setNonStrokingColor(new Color(NAVY_RED, NAVY_GREEN, NAVY_BLUE));
                content.addRect(MARGIN, rowBottom, width, rowHeight);
                content.fill();
            }

            content.setStrokingColor(header
                    ? new Color(NAVY_RED, NAVY_GREEN, NAVY_BLUE)
                    : new Color(205, 213, 220));
            content.setLineWidth(0.55f);
            content.addRect(MARGIN, rowBottom, width, rowHeight);
            float x = MARGIN;
            for (int index = 0; index < widths.length - 1; index++) {
                x += widths[index];
                content.moveTo(x, rowBottom);
                content.lineTo(x, rowTop);
            }
            content.stroke();

            x = MARGIN;
            for (int index = 0; index < wrapped.size(); index++) {
                float baseline = rowTop - 15;
                for (String line : wrapped.get(index)) {
                    drawText(line, x + 5, baseline, font, fontSize,
                            header ? 255 : 35,
                            header ? 255 : 51,
                            header ? 255 : 65);
                    baseline -= leading;
                }
                x += widths[index];
            }
            y = rowBottom;
        }

        private float tableRowHeight(String[] values, float[] widths, boolean header) throws IOException {
            PDFont font = header ? bold : regular;
            float fontSize = header ? 10 : 9.5f;
            float leading = header ? 12.5f : 12.2f;
            int maxLines = 1;
            for (int index = 0; index < values.length; index++) {
                maxLines = Math.max(maxLines, wrap(safe(values[index]), font, fontSize, widths[index] - 10).size());
            }
            return Math.max(header ? 29 : 31, (maxLines * leading) + 13);
        }

        void packageRow(
                String heading,
                String route,
                String assignment,
                String finance,
                String flags,
            String note) throws IOException {
            ensure(86);
            writeWrapped(safe(heading), MARGIN + 8, width - 16, bold, 9.3f, 12,
                    NAVY_RED, NAVY_GREEN, NAVY_BLUE);
            writeWrapped(safe(route), MARGIN + 8, width - 16, regular, 8.2f, 10.5f, 45, 58, 68);
            if (assignment != null && !assignment.isBlank() && !"—".equals(assignment)) {
                writeWrapped(safe(assignment), MARGIN + 8, width - 16, regular, 7.8f, 10, 88, 100, 110);
            }
            writeWrapped(safe(finance), MARGIN + 8, width - 16, bold, 8.1f, 10.5f,
                    ORANGE_RED, ORANGE_GREEN, ORANGE_BLUE);
            if (flags != null && !flags.isBlank() && !"—".equals(flags)) {
                writeWrapped(safe(flags), MARGIN + 8, width - 16, regular, 7.7f, 9.5f, 88, 100, 110);
            }
            if (note != null && !note.isBlank()) {
                writeWrapped("Note : " + safe(note), MARGIN + 8, width - 16, regular, 7.7f, 9.5f, 88, 100, 110);
            }
            separator();
        }

        void gap(float points) {
            y -= points;
        }

        private void separator() throws IOException {
            y -= 4;
            content.setStrokingColor(new Color(218, 224, 229));
            content.setLineWidth(0.5f);
            content.moveTo(MARGIN, y);
            content.lineTo(MARGIN + width, y);
            content.stroke();
            y -= 10;
        }

        private void writeWrapped(
                String value,
                float x,
                float maxWidth,
                PDFont font,
                float fontSize,
                float leading,
                int red,
                int green,
                int blue) throws IOException {
            List<String> lines = wrap(value, font, fontSize, maxWidth);
            for (String line : lines) {
                ensure(leading + 2);
                drawText(line, x, y, font, fontSize, red, green, blue);
                y -= leading;
            }
        }

        private List<String> wrap(String value, PDFont font, float fontSize, float maxWidth) throws IOException {
            String normalized = safe(value);
            if (normalized.isBlank()) return List.of("—");
            List<String> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : normalized.split("\\s+")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (!current.isEmpty() && width(candidate, font, fontSize) > maxWidth) {
                    result.add(current.toString());
                    current.setLength(0);
                    if (width(word, font, fontSize) > maxWidth) {
                        splitLongWord(word, font, fontSize, maxWidth, result, current);
                    } else {
                        current.append(word);
                    }
                } else {
                    current.setLength(0);
                    current.append(candidate);
                }
            }
            if (!current.isEmpty()) result.add(current.toString());
            return result;
        }

        private void splitLongWord(
                String word,
                PDFont font,
                float fontSize,
                float maxWidth,
                List<String> result,
                StringBuilder remainder) throws IOException {
            for (int index = 0; index < word.length(); index++) {
                char character = word.charAt(index);
                String candidate = remainder.toString() + character;
                if (!remainder.isEmpty() && width(candidate, font, fontSize) > maxWidth) {
                    result.add(remainder.toString());
                    remainder.setLength(0);
                }
                remainder.append(character);
            }
        }

        private float width(String value, PDFont font, float fontSize) throws IOException {
            return font.getStringWidth(value) / 1000f * fontSize;
        }

        private void ensure(float required) throws IOException {
            if (y - required < FOOTER_HEIGHT + 12) openPage();
        }

        private void drawText(
                String value,
                float x,
                float baseline,
                PDFont font,
                float fontSize,
                int red,
                int green,
                int blue) throws IOException {
            content.beginText();
            content.setFont(font, fontSize);
            content.setNonStrokingColor(new Color(red, green, blue));
            content.newLineAtOffset(x, baseline);
            content.showText(safe(value));
            content.endText();
        }

        private void drawWrappedAt(
                String value,
                float x,
                float baseline,
                float maxWidth,
                PDFont font,
                float fontSize,
                float leading,
                int red,
                int green,
                int blue) throws IOException {
            float currentBaseline = baseline;
            for (String line : wrap(value, font, fontSize, maxWidth)) {
                drawText(line, x, currentBaseline, font, fontSize, red, green, blue);
                currentBaseline -= leading;
            }
        }

        private static String safe(String value) {
            if (value == null) return "—";
            String flattened = value
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .replace('\u00a0', ' ')
                    .replace('\u202f', ' ')
                    .replace("→", " vers ")
                    .strip();
            if (flattened.isEmpty()) return "—";
            return flattened;
        }

        @Override
        public void close() throws IOException {
            if (content != null) {
                content.close();
                content = null;
            }
        }
    }
}
