package com.azylivraison.reporting.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

/** Stateless XLSX renderer. All monetary values are taken verbatim from the report model. */
@Component
public final class DailyReportXlsxRenderer {

    private static final ZoneId ANTANANARIVO = ZoneId.of("Indian/Antananarivo");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);
    private static final DateTimeFormatter DATE_LONG = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE)
            .withZone(ANTANANARIVO);

    public byte[] render(DailyReport report) {
        Objects.requireNonNull(report, "report");
        return renderWorkbook(report, report.totals(), report.clients(), report.couriers(), report.packages(), null);
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
        return renderClientWorkbook(report, client, packages);
    }

    private byte[] renderClientWorkbook(
            DailyReport report,
            ClientSummary client,
            List<PackageLine> packages) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            Sheet sheet = workbook.createSheet("Rapport client");
            clientHeader(sheet, styles, client.displayName(), report.serviceDate());

            int rowIndex = 3;
            if (client.phone() != null && !client.phone().isBlank()) {
                rowIndex = keyValue(sheet, styles, rowIndex, "Contact", client.phone().strip());
            }
            List<String> pickups = pickupLabels(packages);
            String pickupHeading = pickups.size() > 1 ? "Points de récupération" : "Point de récupération";
            if (pickups.isEmpty()) {
                rowIndex = keyValue(sheet, styles, rowIndex, pickupHeading, "—");
            } else {
                for (int index = 0; index < pickups.size(); index++) {
                    rowIndex = keyValue(sheet, styles, rowIndex, index == 0 ? pickupHeading : "", pickups.get(index));
                }
            }
            rowIndex += 2;

            String[] headers = {
                "Destinataire", "Contact", "Destination", "État", "Mobile Money",
                "Commentaire", "Récupéré (Ar)", "Frais (Ar)", "À reverser (Ar)"
            };
            int tableHeaderRow = rowIndex;
            header(sheet, styles, tableHeaderRow, headers);
            rowIndex++;
            for (PackageLine item : packages) {
                Row row = sheet.createRow(rowIndex++);
                int estimatedCommentLines = item.outcomeNote() == null
                        ? 1
                        : Math.max(1, (item.outcomeNote().strip().length() + 54) / 55);
                row.setHeightInPoints(Math.min(390, Math.max(30, estimatedCommentLines * 15)));
                text(row, 0, item.recipientName(), styles.body);
                text(row, 1, item.recipientContact(), styles.body);
                text(row, 2, item.destinationLabel(), styles.body);
                text(row, 3, clientOutcomeLabel(item), styles.body);
                text(row, 4, yesNo(item.mobileMoneyPaid()), styles.information);
                text(row, 5, item.outcomeNote(), styles.body);
                money(row, 6, item.actualAmountCollectedAriary(), styles.money);
                money(row, 7, item.appliedDeliveryFeeAriary(), styles.money);
                money(row, 8, item.amountToRemitAriary(), styles.money);
            }

            int tableLastRow = Math.max(tableHeaderRow, rowIndex - 1);
            rowIndex += 3;
            rowIndex = section(sheet, styles, rowIndex, "Synthèse");
            Row summary = sheet.createRow(rowIndex);
            summary.setHeightInPoints(30);
            text(summary, 0, "Colis", styles.key);
            number(summary, 1, client.totals().packageCount(), styles.integer);
            text(summary, 2, "Total récupéré", styles.key);
            money(summary, 3, client.totals().amountCollectedAriary(), styles.moneyEmphasis);
            text(summary, 4, "Frais de livraison", styles.key);
            money(summary, 5, client.totals().deliveryFeesAriary(), styles.moneyEmphasis);
            text(summary, 6, "Total à reverser", styles.key);
            money(summary, 7, client.totals().amountToRemitAriary(), styles.moneyEmphasis);

            int[] widths = {28, 18, 34, 20, 20, 36, 20, 16, 20};
            for (int index = 0; index < widths.length; index++) {
                sheet.setColumnWidth(index, widths[index] * 256);
            }
            if (tableHeaderRow <= 12) {
                sheet.createFreezePane(0, tableHeaderRow + 1);
            }
            if (!packages.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(tableHeaderRow, tableLastRow, 0, headers.length - 1));
            }
            sheet.getPrintSetup().setLandscape(true);
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Le rapport Excel client n'a pas pu être généré.", exception);
        }
    }

    private byte[] renderWorkbook(
            DailyReport report,
            Totals totals,
            List<ClientSummary> clients,
            List<CourierSummary> couriers,
            List<PackageLine> packages,
            ClientSummary selectedClient) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            writeSummary(workbook, styles, report, totals, selectedClient);
            writeClients(workbook, styles, clients);
            if (!couriers.isEmpty()) writeCouriers(workbook, styles, couriers);
            writePackages(workbook, styles, "Colis", packages);
            writePackages(workbook, styles, "Retours et reports", packages.stream()
                    .filter(item -> item.outcome() == PackageOutcome.RETURN_TO_SENDER
                            || item.outcome() == PackageOutcome.POSTPONED)
                    .toList());
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Le rapport Excel n'a pas pu être généré.", exception);
        }
    }

    private void writeSummary(
            XSSFWorkbook workbook,
            Styles styles,
            DailyReport report,
            Totals totals,
            ClientSummary selectedClient) {
        Sheet sheet = workbook.createSheet("Synthèse");
        title(sheet, styles, selectedClient == null
                ? "AZY Livraison · Rapport général"
                : "AZY Livraison · Rapport client · " + value(selectedClient.displayName()), 4);
        int rowIndex = 2;
        rowIndex = keyValue(sheet, styles, rowIndex, "Date d'activité", DATE.format(report.serviceDate()));
        rowIndex = keyValue(sheet, styles, rowIndex,
                report.sourcePlanCount() > 1 ? "Point de ralliement de référence" : "Point de ralliement",
                value(report.hubLabel()));
        rowIndex = keyValue(sheet, styles, rowIndex,
                report.sourcePlanCount() > 1 ? "Plan de référence" : "Plan",
                report.dispatchPlanId().toString());
        if (report.sourcePlanCount() > 1) {
            rowIndex = keyValue(sheet, styles, rowIndex, "Historique consolidé",
                    report.sourcePlanCount() + " plans de dispatch");
        }
        rowIndex = keyValue(sheet, styles, rowIndex, "Clôturée le", dateTime(report.closure().closedAt()));
        rowIndex = keyValue(sheet, styles, rowIndex, "Clôturée par", value(report.closure().closedByDisplayName()));
        rowIndex = keyValue(sheet, styles, rowIndex, "Version de clôture", report.closure().version());
        rowIndex = keyValue(sheet, styles, rowIndex, "Généré le", dateTime(report.generatedAt()));
        if (selectedClient != null) {
            rowIndex = keyValue(sheet, styles, rowIndex, "Compte client", value(selectedClient.username()));
            rowIndex = keyValue(sheet, styles, rowIndex, "Téléphone", value(selectedClient.phone()));
        }
        rowIndex += 1;
        rowIndex = section(sheet, styles, rowIndex, "Résultats");
        rowIndex = keyValue(sheet, styles, rowIndex, "Colis", totals.packageCount());
        rowIndex = keyValue(sheet, styles, rowIndex, "Livrés", totals.deliveredCount());
        rowIndex = keyValue(sheet, styles, rowIndex, "Annulés", totals.cancelledCount());
        rowIndex = keyValue(sheet, styles, rowIndex, "Reportés", totals.postponedCount());
        rowIndex = keyValue(sheet, styles, rowIndex, "À retourner", totals.returnRequiredCount());
        rowIndex = keyValue(sheet, styles, rowIndex, "Déjà retournés", totals.returnedToSenderCount());
        rowIndex += 1;
        rowIndex = section(sheet, styles, rowIndex, "Totaux financiers");
        rowIndex = moneyKeyValue(sheet, styles, rowIndex, "Total récupéré", totals.amountCollectedAriary());
        rowIndex = moneyKeyValue(sheet, styles, rowIndex, "Frais de livraison", totals.deliveryFeesAriary());
        moneyKeyValue(sheet, styles, rowIndex, "Total à reverser", totals.amountToRemitAriary());
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 48 * 256);
        sheet.setColumnWidth(2, 18 * 256);
        sheet.setColumnWidth(3, 18 * 256);
        sheet.createFreezePane(0, 2);
    }

    private void writeClients(XSSFWorkbook workbook, Styles styles, List<ClientSummary> clients) {
        Sheet sheet = workbook.createSheet("Clients");
        String[] headers = {
            "Compte client", "Identifiant", "Téléphone", "Colis", "Livrés", "Annulés", "Reportés",
            "À retourner", "Déjà retournés", "Total récupéré (Ar)", "Frais (Ar)", "À reverser (Ar)"
        };
        header(sheet, styles, headers);
        int rowIndex = 1;
        for (ClientSummary client : clients) {
            Row row = sheet.createRow(rowIndex++);
            text(row, 0, client.displayName(), styles.body);
            text(row, 1, client.username(), styles.body);
            text(row, 2, client.phone(), styles.body);
            countsAndMoney(row, 3, client.totals(), styles);
        }
        finishTable(sheet, headers.length, rowIndex, new int[] {28, 24, 18, 10, 10, 10, 10, 13, 16, 20, 16, 20});
    }

    private void writeCouriers(XSSFWorkbook workbook, Styles styles, List<CourierSummary> couriers) {
        Sheet sheet = workbook.createSheet("Livreurs");
        String[] headers = {
            "Livreur", "Contact", "Véhicule", "Colis", "Livrés", "Annulés", "Reportés",
            "À retourner", "Déjà retournés", "Total récupéré (Ar)", "Frais (Ar)", "À reverser (Ar)"
        };
        header(sheet, styles, headers);
        int rowIndex = 1;
        for (CourierSummary courier : couriers) {
            Row row = sheet.createRow(rowIndex++);
            text(row, 0, courier.displayName(), styles.body);
            text(row, 1, courier.contact(), styles.body);
            text(row, 2, courier.vehicleType(), styles.body);
            countsAndMoney(row, 3, courier.totals(), styles);
        }
        finishTable(sheet, headers.length, rowIndex, new int[] {28, 18, 14, 10, 10, 10, 10, 13, 16, 20, 16, 20});
    }

    private void writePackages(
            XSSFWorkbook workbook,
            Styles styles,
            String sheetName,
            List<PackageLine> packages) {
        Sheet sheet = workbook.createSheet(sheetName);
        String[] headers = {
            "Colis", "Demande", "N°", "Compte client", "Destinataire", "Contact", "Retrait",
            "Destination", "Zone", "État final", "Livreur prévu", "Livreur réel",
            "Montant prévu (Ar)", "Récupéré (Ar)",
            "Frais appliqués (Ar)", "À reverser (Ar)", "Mobile Money (informatif)", "Faritra",
            "Finalisé le", "Retour", "Retourné le", "Nouveau colis", "Reporté au", "Nouveau retrait", "Note"
        };
        header(sheet, styles, headers);
        int rowIndex = 1;
        for (PackageLine item : packages) {
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            text(row, column++, item.packageId().toString(), styles.identifier);
            text(row, column++, item.deliveryRequestId().toString(), styles.identifier);
            number(row, column++, item.sequenceNumber(), styles.integer);
            text(row, column++, item.clientDisplayName(), styles.body);
            text(row, column++, item.recipientName(), styles.body);
            text(row, column++, item.recipientContact(), styles.body);
            text(row, column++, item.pickupLabel(), styles.body);
            text(row, column++, item.destinationLabel(), styles.body);
            text(row, column++, item.pricingZoneName(), styles.body);
            text(row, column++, item.outcome().frenchLabel(), styles.body);
            text(row, column++, item.plannedCourierName(), styles.body);
            text(row, column++, actualCourierLabel(item), styles.body);
            money(row, column++, item.expectedAmountToCollectAriary(), styles.money);
            money(row, column++, item.actualAmountCollectedAriary(), styles.money);
            money(row, column++, item.appliedDeliveryFeeAriary(), styles.money);
            money(row, column++, item.amountToRemitAriary(), styles.money);
            text(row, column++, yesNo(item.mobileMoneyPaid()), styles.information);
            text(row, column++, yesNo(item.faritra()), styles.body);
            text(row, column++, dateTime(item.finalizedAt()), styles.body);
            text(row, column++, returnLabel(item), styles.body);
            text(row, column++, dateTime(item.returnedToSenderAt()), styles.body);
            text(row, column++, item.replacementPackageId() == null ? null : item.replacementPackageId().toString(),
                    styles.identifier);
            text(row, column++, item.postponedToDate() == null ? null : DATE.format(item.postponedToDate()), styles.body);
            text(row, column++, item.replacementPickupLabel(), styles.body);
            text(row, column, item.outcomeNote(), styles.body);
        }
        finishTable(sheet, headers.length, rowIndex,
                new int[] {38, 38, 7, 25, 25, 18, 28, 30, 18, 16, 22, 22, 18, 18, 20, 18, 24, 11, 20,
                    21, 20, 38, 16, 28, 34});
    }

    private static String actualCourierLabel(PackageLine item) {
        if (item.actualCourierName() != null && !item.actualCourierName().isBlank()) {
            return item.actualCourierName();
        }
        return item.outcome() == PackageOutcome.DELIVERED
                || item.outcome() == PackageOutcome.RETURN_TO_SENDER
                ? "Non renseigné (historique)"
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

    private void title(Sheet sheet, Styles styles, String title, int width) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(28);
        Cell cell = row.createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(styles.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, width - 1));
    }

    private void clientHeader(Sheet sheet, Styles styles, String clientName, java.time.LocalDate serviceDate) {
        title(sheet, styles, "AZY Livraison · Rapport client", 9);
        Row identity = sheet.createRow(1);
        identity.setHeightInPoints(34);
        for (int column = 0; column < 9; column++) {
            Cell cell = identity.createCell(column);
            cell.setCellStyle(column < 6 ? styles.clientName : styles.clientDate);
        }
        identity.getCell(0).setCellValue(value(clientName));
        identity.getCell(6).setCellValue(DATE_LONG.format(serviceDate));
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 5));
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 6, 8));
    }

    private int section(Sheet sheet, Styles styles, int rowIndex, String title) {
        Row row = sheet.createRow(rowIndex);
        Cell cell = row.createCell(0);
        cell.setCellValue(title);
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

    private int keyValue(Sheet sheet, Styles styles, int rowIndex, String key, long value) {
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

    private void header(Sheet sheet, Styles styles, String[] headers) {
        header(sheet, styles, 0, headers);
    }

    private void header(Sheet sheet, Styles styles, int rowIndex, String[] headers) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(30);
        for (int index = 0; index < headers.length; index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(headers[index]);
            cell.setCellStyle(styles.header);
        }
    }

    private void countsAndMoney(Row row, int startColumn, Totals totals, Styles styles) {
        int column = startColumn;
        number(row, column++, totals.packageCount(), styles.integer);
        number(row, column++, totals.deliveredCount(), styles.integer);
        number(row, column++, totals.cancelledCount(), styles.integer);
        number(row, column++, totals.postponedCount(), styles.integer);
        number(row, column++, totals.returnRequiredCount(), styles.integer);
        number(row, column++, totals.returnedToSenderCount(), styles.integer);
        money(row, column++, totals.amountCollectedAriary(), styles.money);
        money(row, column++, totals.deliveryFeesAriary(), styles.money);
        money(row, column, totals.amountToRemitAriary(), styles.money);
    }

    private void finishTable(Sheet sheet, int columnCount, int rowCount, int[] widths) {
        sheet.createFreezePane(0, 1);
        if (rowCount > 1) sheet.setAutoFilter(new CellRangeAddress(0, rowCount - 1, 0, columnCount - 1));
        for (int index = 0; index < Math.min(widths.length, columnCount); index++) {
            sheet.setColumnWidth(index, Math.min(widths[index], 255) * 256);
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

    private static String value(String value) {
        return value == null || value.isBlank() ? "—" : value.strip();
    }

    private static String yesNo(boolean value) {
        return value ? "Oui" : "Non";
    }

    private static String returnLabel(PackageLine item) {
        if (item.outcome() != PackageOutcome.RETURN_TO_SENDER) return "Non concerné";
        return item.returnedToSender() ? "Retourné à l'expéditeur" : "Retour en attente";
    }

    private static String dateTime(Instant value) {
        return value == null ? "—" : DATE_TIME.format(value);
    }

    private static final class Styles {

        private final XSSFCellStyle title;
        private final XSSFCellStyle clientName;
        private final XSSFCellStyle clientDate;
        private final XSSFCellStyle section;
        private final XSSFCellStyle header;
        private final XSSFCellStyle key;
        private final XSSFCellStyle body;
        private final XSSFCellStyle identifier;
        private final XSSFCellStyle integer;
        private final XSSFCellStyle money;
        private final XSSFCellStyle moneyEmphasis;
        private final XSSFCellStyle information;

        private Styles(XSSFWorkbook workbook) {
            XSSFColor navy = new XSSFColor(new byte[] {11, 53, 87});
            XSSFColor orange = new XSSFColor(new byte[] {(byte) 244, 91, 11});
            XSSFColor paleOrange = new XSSFColor(new byte[] {(byte) 255, (byte) 239, (byte) 229});

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

            clientName = workbook.createCellStyle();
            clientName.setFillForegroundColor(navy);
            clientName.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            clientName.setFont(font(workbook, true, IndexedColors.WHITE.getIndex(), 16));
            clientName.setVerticalAlignment(VerticalAlignment.CENTER);

            clientDate = workbook.createCellStyle();
            clientDate.cloneStyleFrom(clientName);
            clientDate.setAlignment(HorizontalAlignment.RIGHT);

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

            information = workbook.createCellStyle();
            information.cloneStyleFrom(body);
            information.setFillForegroundColor(paleOrange);
            information.setFillPattern(FillPatternType.SOLID_FOREGROUND);
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
