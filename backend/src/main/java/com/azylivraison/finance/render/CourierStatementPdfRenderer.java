package com.azylivraison.finance.render;

import static com.azylivraison.finance.domain.FinanceModels.*;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

/** Produces an administrator-facing, immutable courier financial statement. */
@Component
public final class CourierStatementPdfRenderer {

    private static final ZoneId ANTANANARIVO = ZoneId.of("Indian/Antananarivo");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE)
            .withZone(ANTANANARIVO);
    private static final NumberFormat INTEGER = NumberFormat.getIntegerInstance(Locale.FRANCE);

    public byte[] render(CourierFinance finance, LocalDate from, LocalDate to, Instant generatedAt) {
        Objects.requireNonNull(finance, "finance");
        Objects.requireNonNull(generatedAt, "generatedAt");
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            try (PdfWriter writer = new PdfWriter(document, finance, from, to, generatedAt)) {
                writer.openPage();
                writeIdentity(writer, finance, from, to);
                writeTotals(writer, finance);
                writeDeliveries(writer, finance.deliveries());
                writeEarnings(writer, finance.earnings());
                writePayments(writer, finance.payments());
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Le relevé financier PDF du livreur n'a pas pu être généré.", exception);
        }
    }

    private void writeIdentity(PdfWriter writer, CourierFinance finance, LocalDate from, LocalDate to)
            throws IOException {
        writer.section("Identité et période");
        writer.line("Livreur", text(finance.courierName()));
        writer.line("Identifiant", finance.courierId().toString());
        writer.line("Période détaillée", period(from, to));
        writer.paragraph("Les totaux représentent la situation globale du livreur. La période filtre les listes "
                + "de livraisons, de rémunérations et de versements ci-dessous.");
        writer.gap(5);
    }

    private void writeTotals(PdfWriter writer, CourierFinance finance) throws IOException {
        writer.section("Situation financière globale");
        writer.emphasis("Total gagné", ariary(finance.totalEarnedAriary()));
        writer.emphasis("Total versé", ariary(finance.totalPaidAriary()));
        writer.emphasis("Reste à verser", ariary(finance.remainingToPayAriary()));
        writer.emphasis("Avance ou crédit", ariary(finance.advanceCreditAriary()));
        writer.gap(5);
    }

    private void writeDeliveries(PdfWriter writer, List<CourierDelivery> deliveries) throws IOException {
        writer.section("Livraisons effectuées");
        if (deliveries.isEmpty()) {
            writer.paragraph("Aucune livraison pour cette période.");
        }
        for (CourierDelivery delivery : deliveries) {
            String earned = delivery.earnedAmountAriary() == null
                    ? "Rémunération non finalisée"
                    : "Rémunération : " + ariary(delivery.earnedAmountAriary());
            writer.entry(
                    DATE.format(delivery.serviceDate()) + " · " + text(delivery.recipientName()),
                    text(delivery.clientDisplayName()) + " · " + outcome(delivery.outcome()),
                    text(delivery.pickupLabel()) + " vers " + text(delivery.destinationLabel()),
                    "Frais : " + ariary(delivery.deliveryFeeAriary()) + " · " + earned,
                    "Colis " + delivery.packageId());
        }
        writer.gap(5);
    }

    private void writeEarnings(PdfWriter writer, List<CourierEarning> earnings) throws IOException {
        writer.section("Rémunérations figées");
        if (earnings.isEmpty()) {
            writer.paragraph("Aucune rémunération finalisée pour cette période.");
        }
        for (CourierEarning earning : earnings) {
            String adjustment = earning.overrideReason() == null
                    ? "Montant suggéré conservé"
                    : "Ajustement : " + earning.overrideReason();
            writer.entry(
                    DATE.format(earning.serviceDate()) + " · " + outcome(earning.outcome()),
                    "Fraction figée : " + earning.commissionNumerator() + "/" + earning.commissionDenominator()
                            + " · Figée le " + dateTime(earning.createdAt()),
                    "Frais : " + ariary(earning.deliveryFeeAriary())
                            + " · Suggéré : " + ariary(earning.suggestedAmountAriary())
                            + " · Final : " + ariary(earning.finalAmountAriary()),
                    adjustment,
                    "Colis " + earning.packageId() + " · Révision " + earning.closureRevision());
        }
        writer.gap(5);
    }

    private void writePayments(PdfWriter writer, List<CourierPayment> payments) throws IOException {
        writer.section("Versements");
        if (payments.isEmpty()) {
            writer.paragraph("Aucun versement pour cette période.");
        }
        for (CourierPayment payment : payments) {
            String status = payment.status() == PaymentStatus.VOIDED ? "ANNULÉ" : "CONFIRMÉ";
            String destination = destination(payment.destination());
            String proof = payment.proof() == null
                    ? "Aucun justificatif"
                    : "Justificatif : " + text(payment.proof().originalFilename());
            String cancellation = payment.status() == PaymentStatus.VOIDED
                    ? "Annulé le " + dateTime(payment.voidedAt()) + " · Motif : " + text(payment.voidReason())
                    : null;
            writer.entry(
                    dateTime(payment.confirmedAt()) + " · " + status + " · " + ariary(payment.amountAriary()),
                    "Méthode : " + text(payment.paymentMethod()) + " · " + destination,
                    "Référence : " + text(payment.transactionReference()) + " · " + proof,
                    join(" · ", cancellation, payment.note() == null ? null : "Note : " + payment.note()),
                    "Versement " + payment.id());
        }
    }

    private static String period(LocalDate from, LocalDate to) {
        if (from == null && to == null) return "Toutes les dates";
        if (from == null) return "Jusqu'au " + DATE.format(to);
        if (to == null) return "Depuis le " + DATE.format(from);
        return "Du " + DATE.format(from) + " au " + DATE.format(to);
    }

    private static String ariary(long value) {
        return INTEGER.format(value) + " Ar";
    }

    private static String dateTime(Instant value) {
        return value == null ? "—" : DATE_TIME.format(value);
    }

    private static String outcome(DeliveryPackageOutcome value) {
        return switch (value) {
            case DELIVERED -> "Livré";
            case RETURN_TO_SENDER -> "Retourné à l'expéditeur";
            case CANCELLED -> "Annulé";
            case POSTPONED -> "Reporté";
        };
    }

    private static String destination(PaymentDestination value) {
        if (value == null) return "Destination non renseignée";
        return join(" · ", value.operator(), value.accountHolder(), value.number());
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "—" : value.strip();
    }

    private static String join(String delimiter, String... values) {
        List<String> kept = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !"—".equals(value)) kept.add(value.strip());
        }
        return kept.isEmpty() ? "—" : String.join(delimiter, kept);
    }

    private static final class PdfWriter implements AutoCloseable {

        private static final float MARGIN = 42;
        private static final float FOOTER_HEIGHT = 28;
        private static final float TOP = PDRectangle.A4.getHeight() - MARGIN;
        private static final float WIDTH = PDRectangle.A4.getWidth() - (2 * MARGIN);
        private static final String FONT_RESOURCE = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
        private static final int NAVY_RED = 11;
        private static final int NAVY_GREEN = 53;
        private static final int NAVY_BLUE = 87;
        private static final int ORANGE_RED = 244;
        private static final int ORANGE_GREEN = 91;
        private static final int ORANGE_BLUE = 11;

        private final PDDocument document;
        private final CourierFinance finance;
        private final LocalDate from;
        private final LocalDate to;
        private final Instant generatedAt;
        private final PDFont regular;
        private final PDFont bold;
        private PDPageContentStream content;
        private float y;
        private int pageNumber;

        private PdfWriter(
                PDDocument document,
                CourierFinance finance,
                LocalDate from,
                LocalDate to,
                Instant generatedAt) throws IOException {
            this.document = document;
            this.finance = finance;
            this.from = from;
            this.to = to;
            this.generatedAt = generatedAt;
            try (InputStream fontStream = CourierStatementPdfRenderer.class.getResourceAsStream(FONT_RESOURCE)) {
                if (fontStream == null) throw new IOException("La police Unicode du relevé est introuvable.");
                regular = PDType0Font.load(document, fontStream, true);
                bold = regular;
            }
        }

        private void openPage() throws IOException {
            if (content != null) content.close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            pageNumber++;
            content.setNonStrokingColor(new Color(NAVY_RED, NAVY_GREEN, NAVY_BLUE));
            content.addRect(0, PDRectangle.A4.getHeight() - 112, PDRectangle.A4.getWidth(), 112);
            content.fill();
            content.setNonStrokingColor(new Color(ORANGE_RED, ORANGE_GREEN, ORANGE_BLUE));
            content.addRect(0, PDRectangle.A4.getHeight() - 117, PDRectangle.A4.getWidth(), 5);
            content.fill();
            drawText("AZY LIVRAISON", MARGIN, TOP - 13, bold, 17, 255, 255, 255);
            drawText("Relevé financier livreur", MARGIN, TOP - 38, regular, 11, 255, 255, 255);
            drawText(safe(finance.courierName()) + " · " + period(from, to), MARGIN, TOP - 61,
                    regular, 9, 220, 230, 238);
            drawText("Page " + pageNumber + " · Généré le " + dateTime(generatedAt),
                    MARGIN, 17, regular, 7.5f, 90, 104, 116);
            y = PDRectangle.A4.getHeight() - 137;
        }

        private void section(String value) throws IOException {
            ensure(28);
            content.setNonStrokingColor(new Color(ORANGE_RED, ORANGE_GREEN, ORANGE_BLUE));
            content.addRect(MARGIN, y - 3, 4, 17);
            content.fill();
            drawText(safe(value), MARGIN + 10, y, bold, 12, NAVY_RED, NAVY_GREEN, NAVY_BLUE);
            y -= 23;
        }

        private void line(String label, String value) throws IOException {
            ensure(16);
            drawText(safe(label), MARGIN, y, bold, 8.5f, 45, 58, 68);
            writeWrapped(safe(value), MARGIN + 112, WIDTH - 112, regular, 8.5f, 11, 45, 58, 68);
            y -= 3;
        }

        private void emphasis(String label, String value) throws IOException {
            ensure(22);
            drawText(safe(label), MARGIN, y, regular, 9, 70, 82, 92);
            drawText(safe(value), MARGIN + 190, y, bold, 11, NAVY_RED, NAVY_GREEN, NAVY_BLUE);
            y -= 18;
        }

        private void paragraph(String value) throws IOException {
            writeWrapped(safe(value), MARGIN, WIDTH, regular, 8.5f, 11, 55, 68, 78);
        }

        private void entry(String heading, String metadata, String details, String note, String identifier)
                throws IOException {
            ensure(60);
            writeWrapped(safe(heading), MARGIN + 8, WIDTH - 16, bold, 9.3f, 12,
                    NAVY_RED, NAVY_GREEN, NAVY_BLUE);
            writeOptional(metadata, 7.8f, 10, 88, 100, 110);
            writeOptional(details, 8.2f, 10.5f, 45, 58, 68);
            writeOptional(note, 7.8f, 10, ORANGE_RED, ORANGE_GREEN, ORANGE_BLUE);
            writeOptional(identifier, 7.2f, 9.5f, 105, 116, 126);
            separator();
        }

        private void writeOptional(String value, float size, float leading, int red, int green, int blue)
                throws IOException {
            if (value != null && !value.isBlank() && !"—".equals(value)) {
                writeWrapped(safe(value), MARGIN + 8, WIDTH - 16, regular, size, leading, red, green, blue);
            }
        }

        private void separator() throws IOException {
            y -= 4;
            content.setStrokingColor(new Color(218, 224, 229));
            content.setLineWidth(0.5f);
            content.moveTo(MARGIN, y);
            content.lineTo(MARGIN + WIDTH, y);
            content.stroke();
            y -= 10;
        }

        private void gap(float points) {
            y -= points;
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
            ensure(lines.size() * leading + 2);
            for (String line : lines) {
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
                String candidate = remainder.toString() + word.charAt(index);
                if (!remainder.isEmpty() && width(candidate, font, fontSize) > maxWidth) {
                    result.add(remainder.toString());
                    remainder.setLength(0);
                }
                remainder.append(word.charAt(index));
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

        private static String safe(String value) {
            if (value == null) return "—";
            String flattened = value.replace('\r', ' ').replace('\n', ' ')
                    .replace('\u00a0', ' ').replace('\u202f', ' ').replace("→", " vers ").strip();
            return flattened.isEmpty() ? "—" : flattened;
        }

        @Override
        public void close() throws IOException {
            if (content != null) content.close();
        }
    }
}
