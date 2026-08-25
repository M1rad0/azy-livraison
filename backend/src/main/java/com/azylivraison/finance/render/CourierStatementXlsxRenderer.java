package com.azylivraison.finance.render;

import static com.azylivraison.finance.domain.FinanceModels.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

import com.azylivraison.delivery.domain.DeliveryPackageOutcome;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Produces a filterable workbook while preserving amounts as numeric cells. */
@Component
public final class CourierStatementXlsxRenderer {

    private static final ZoneId ANTANANARIVO = ZoneId.of("Indian/Antananarivo");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE)
            .withZone(ANTANANARIVO);

    public byte[] render(CourierFinance finance, LocalDate from, LocalDate to, Instant generatedAt) {
        Objects.requireNonNull(finance, "finance");
        Objects.requireNonNull(generatedAt, "generatedAt");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            writeSummary(workbook, styles, finance, from, to, generatedAt);
            writeDeliveries(workbook, styles, finance);
            writeEarnings(workbook, styles, finance);
            writePayments(workbook, styles, finance);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Le relevé financier Excel du livreur n'a pas pu être généré.", exception);
        }
    }

    private void writeSummary(
            XSSFWorkbook workbook,
            Styles styles,
            CourierFinance finance,
            LocalDate from,
            LocalDate to,
            Instant generatedAt) {
        Sheet sheet = workbook.createSheet("Synthèse");
        title(sheet, styles, "AZY Livraison · Relevé financier livreur", 4);
        int row = 2;
        row = keyValue(sheet, styles, row, "Livreur", finance.courierName());
        row = keyValue(sheet, styles, row, "Identifiant", finance.courierId().toString());
        row = keyValue(sheet, styles, row, "Période détaillée", period(from, to));
        row = keyValue(sheet, styles, row, "Généré le", dateTime(generatedAt));
        row++;
        row = section(sheet, styles, row, "Situation financière globale");
        row = moneyKeyValue(sheet, styles, row, "Total gagné", finance.totalEarnedAriary());
        row = moneyKeyValue(sheet, styles, row, "Total versé", finance.totalPaidAriary());
        row = moneyKeyValue(sheet, styles, row, "Reste à verser", finance.remainingToPayAriary());
        row = moneyKeyValue(sheet, styles, row, "Avance ou crédit", finance.advanceCreditAriary());
        row++;
        row = section(sheet, styles, row, "Contenu de la période");
        row = numberKeyValue(sheet, styles, row, "Livraisons", finance.deliveries().size());
        row = numberKeyValue(sheet, styles, row, "Rémunérations figées", finance.earnings().size());
        numberKeyValue(sheet, styles, row, "Versements, annulations incluses", finance.payments().size());
        sheet.setColumnWidth(0, 31 * 256);
        sheet.setColumnWidth(1, 48 * 256);
        sheet.setColumnWidth(2, 18 * 256);
        sheet.setColumnWidth(3, 18 * 256);
        sheet.createFreezePane(0, 2);
    }

    private void writeDeliveries(XSSFWorkbook workbook, Styles styles, CourierFinance finance) {
        Sheet sheet = workbook.createSheet("Livraisons");
        String[] headers = {
            "Date", "Compte client", "Destinataire", "Retrait", "Destination", "État",
            "Frais (Ar)", "Rémunération (Ar)", "Retour confirmé le", "Colis"
        };
        header(sheet, styles, headers);
        int rowIndex = 1;
        for (CourierDelivery delivery : finance.deliveries()) {
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            text(row, column++, DATE.format(delivery.serviceDate()), styles.body);
            text(row, column++, delivery.clientDisplayName(), styles.body);
            text(row, column++, delivery.recipientName(), styles.body);
            text(row, column++, delivery.pickupLabel(), styles.body);
            text(row, column++, delivery.destinationLabel(), styles.body);
            text(row, column++, outcome(delivery.outcome()), styles.body);
            money(row, column++, delivery.deliveryFeeAriary(), styles.money);
            nullableMoney(row, column++, delivery.earnedAmountAriary(), styles);
            text(row, column++, dateTime(delivery.returnCompletedAt()), styles.body);
            text(row, column, delivery.packageId().toString(), styles.identifier);
        }
        finishTable(sheet, headers.length, rowIndex, new int[] {14, 26, 25, 28, 30, 24, 16, 22, 20, 38});
    }

    private void writeEarnings(XSSFWorkbook workbook, Styles styles, CourierFinance finance) {
        Sheet sheet = workbook.createSheet("Rémunérations");
        String[] headers = {
            "Date", "État", "Frais (Ar)", "Numérateur", "Dénominateur", "Fraction",
            "Suggéré (Ar)", "Final (Ar)", "Motif d'ajustement", "Figée le", "Révision", "Colis"
        };
        header(sheet, styles, headers);
        int rowIndex = 1;
        for (CourierEarning earning : finance.earnings()) {
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            text(row, column++, DATE.format(earning.serviceDate()), styles.body);
            text(row, column++, outcome(earning.outcome()), styles.body);
            money(row, column++, earning.deliveryFeeAriary(), styles.money);
            number(row, column++, earning.commissionNumerator(), styles.integer);
            number(row, column++, earning.commissionDenominator(), styles.integer);
            text(row, column++, earning.commissionNumerator() + "/" + earning.commissionDenominator(), styles.body);
            money(row, column++, earning.suggestedAmountAriary(), styles.money);
            money(row, column++, earning.finalAmountAriary(), styles.moneyEmphasis);
            text(row, column++, earning.overrideReason(), styles.body);
            text(row, column++, dateTime(earning.createdAt()), styles.body);
            number(row, column++, earning.closureRevision(), styles.integer);
            text(row, column, earning.packageId().toString(), styles.identifier);
        }
        finishTable(sheet, headers.length, rowIndex,
                new int[] {14, 24, 16, 13, 15, 12, 18, 18, 34, 20, 11, 38});
    }

    private void writePayments(XSSFWorkbook workbook, Styles styles, CourierFinance finance) {
        Sheet sheet = workbook.createSheet("Versements");
        String[] headers = {
            "Confirmé le", "Montant (Ar)", "Statut", "Méthode", "Opérateur", "Titulaire", "Numéro",
            "Référence", "Justificatif", "Note", "Annulé le", "Motif d'annulation", "Versement"
        };
        header(sheet, styles, headers);
        int rowIndex = 1;
        for (CourierPayment payment : finance.payments()) {
            Row row = sheet.createRow(rowIndex++);
            CellStyle body = payment.status() == PaymentStatus.VOIDED ? styles.voided : styles.body;
            CellStyle amount = payment.status() == PaymentStatus.VOIDED ? styles.voidedMoney : styles.money;
            int column = 0;
            text(row, column++, dateTime(payment.confirmedAt()), body);
            money(row, column++, payment.amountAriary(), amount);
            text(row, column++, payment.status() == PaymentStatus.VOIDED ? "ANNULÉ" : "CONFIRMÉ", body);
            text(row, column++, payment.paymentMethod(), body);
            text(row, column++, destinationOperator(payment.destination()), body);
            text(row, column++, destinationHolder(payment.destination()), body);
            text(row, column++, destinationNumber(payment.destination()), body);
            text(row, column++, payment.transactionReference(), body);
            text(row, column++, payment.proof() == null ? null : payment.proof().originalFilename(), body);
            text(row, column++, payment.note(), body);
            text(row, column++, dateTime(payment.voidedAt()), body);
            text(row, column++, payment.voidReason(), body);
            text(row, column, payment.id().toString(), styles.identifier);
        }
        finishTable(sheet, headers.length, rowIndex,
                new int[] {20, 18, 13, 18, 18, 24, 19, 24, 28, 32, 20, 34, 38});
    }

    private static String period(LocalDate from, LocalDate to) {
        if (from == null && to == null) return "Toutes les dates";
        if (from == null) return "Jusqu'au " + DATE.format(to);
        if (to == null) return "Depuis le " + DATE.format(from);
        return "Du " + DATE.format(from) + " au " + DATE.format(to);
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

    private void title(Sheet sheet, Styles styles, String value, int columns) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(28);
        Cell cell = row.createCell(0);
        cell.setCellValue(value);
        cell.setCellStyle(styles.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, columns - 1));
    }

    private int section(Sheet sheet, Styles styles, int rowIndex, String value) {
        Row row = sheet.createRow(rowIndex);
        Cell cell = row.createCell(0);
        cell.setCellValue(value);
        cell.setCellStyle(styles.section);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 1));
        return rowIndex + 1;
    }

    private int keyValue(Sheet sheet, Styles styles, int rowIndex, String key, String value) {
        Row row = sheet.createRow(rowIndex);
        text(row, 0, key, styles.key);
        text(row, 1, value, styles.body);
        return rowIndex + 1;
    }

    private int numberKeyValue(Sheet sheet, Styles styles, int rowIndex, String key, long value) {
        Row row = sheet.createRow(rowIndex);
        text(row, 0, key, styles.key);
        number(row, 1, value, styles.integer);
        return rowIndex + 1;
    }

    private int moneyKeyValue(Sheet sheet, Styles styles, int rowIndex, String key, long value) {
        Row row = sheet.createRow(rowIndex);
        text(row, 0, key, styles.key);
        money(row, 1, value, styles.moneyEmphasis);
        return rowIndex + 1;
    }

    private void header(Sheet sheet, Styles styles, String[] values) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(30);
        for (int index = 0; index < values.length; index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(values[index]);
            cell.setCellStyle(styles.header);
        }
    }

    private void finishTable(Sheet sheet, int columns, int rows, int[] widths) {
        sheet.createFreezePane(0, 1);
        if (rows > 1) sheet.setAutoFilter(new CellRangeAddress(0, rows - 1, 0, columns - 1));
        for (int index = 0; index < Math.min(widths.length, columns); index++) {
            sheet.setColumnWidth(index, widths[index] * 256);
        }
    }

    private void text(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value(value));
        cell.setCellStyle(style);
    }

    private void number(Row row, int column, long value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private void money(Row row, int column, long value, CellStyle style) {
        number(row, column, value, style);
    }

    private void nullableMoney(Row row, int column, Long value, Styles styles) {
        if (value == null) {
            text(row, column, null, styles.body);
        } else {
            money(row, column, value, styles.money);
        }
    }

    private static String destinationOperator(PaymentDestination destination) {
        return destination == null ? null : destination.operator();
    }

    private static String destinationHolder(PaymentDestination destination) {
        return destination == null ? null : destination.accountHolder();
    }

    private static String destinationNumber(PaymentDestination destination) {
        return destination == null ? null : destination.number();
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "—" : value.strip();
    }

    private static final class Styles {

        private final XSSFCellStyle title;
        private final XSSFCellStyle section;
        private final XSSFCellStyle header;
        private final XSSFCellStyle key;
        private final XSSFCellStyle body;
        private final XSSFCellStyle identifier;
        private final XSSFCellStyle integer;
        private final XSSFCellStyle money;
        private final XSSFCellStyle moneyEmphasis;
        private final XSSFCellStyle voided;
        private final XSSFCellStyle voidedMoney;

        private Styles(XSSFWorkbook workbook) {
            XSSFColor navy = new XSSFColor(new byte[] {11, 53, 87});
            XSSFColor orange = new XSSFColor(new byte[] {(byte) 244, 91, 11});
            XSSFColor paleRed = new XSSFColor(new byte[] {(byte) 255, (byte) 235, (byte) 235});
            Font whiteBold = font(workbook, true, IndexedColors.WHITE.getIndex(), 11);
            Font titleFont = font(workbook, true, IndexedColors.WHITE.getIndex(), 16);
            Font navyBold = font(workbook, true, IndexedColors.DARK_BLUE.getIndex(), 11);
            Font regular = font(workbook, false, IndexedColors.BLACK.getIndex(), 10);
            Font muted = font(workbook, false, IndexedColors.GREY_50_PERCENT.getIndex(), 9);

            title = workbook.createCellStyle();
            title.setFillForegroundColor(navy);
            title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            title.setFont(titleFont);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            section = workbook.createCellStyle();
            section.setFillForegroundColor(orange);
            section.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            section.setFont(whiteBold);

            header = workbook.createCellStyle();
            header.setFillForegroundColor(navy);
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setFont(whiteBold);
            header.setWrapText(true);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setAlignment(HorizontalAlignment.CENTER);
            borders(header);

            body = workbook.createCellStyle();
            body.setFont(regular);
            body.setVerticalAlignment(VerticalAlignment.TOP);
            body.setWrapText(true);
            borders(body);

            identifier = workbook.createCellStyle();
            identifier.cloneStyleFrom(body);
            identifier.setFont(muted);

            key = workbook.createCellStyle();
            key.cloneStyleFrom(body);
            key.setFont(navyBold);

            integer = workbook.createCellStyle();
            integer.cloneStyleFrom(body);
            integer.setDataFormat(workbook.createDataFormat().getFormat("0"));

            money = workbook.createCellStyle();
            money.cloneStyleFrom(body);
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0 \"Ar\";[Red]-#,##0 \"Ar\""));

            moneyEmphasis = workbook.createCellStyle();
            moneyEmphasis.cloneStyleFrom(money);
            moneyEmphasis.setFont(navyBold);

            voided = workbook.createCellStyle();
            voided.cloneStyleFrom(body);
            voided.setFillForegroundColor(paleRed);
            voided.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            voidedMoney = workbook.createCellStyle();
            voidedMoney.cloneStyleFrom(money);
            voidedMoney.setFillForegroundColor(paleRed);
            voidedMoney.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        private static Font font(XSSFWorkbook workbook, boolean bold, short color, int points) {
            Font font = workbook.createFont();
            font.setBold(bold);
            font.setColor(color);
            font.setFontHeightInPoints((short) points);
            font.setFontName("Arial");
            return font;
        }

        private static void borders(CellStyle style) {
            style.setBorderBottom(BorderStyle.THIN);
            style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        }
    }
}
