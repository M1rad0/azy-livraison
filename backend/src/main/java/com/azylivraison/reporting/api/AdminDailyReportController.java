package com.azylivraison.reporting.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.reporting.service.DailyReportService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery-days/{serviceDate}/reports")
public class AdminDailyReportController {

    static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final DailyReportService service;

    public AdminDailyReportController(DailyReportService service) {
        this.service = service;
    }

    @GetMapping(value = "/general.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> generalPdf(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate) {
        return download(
                service.renderGeneralPdf(serviceDate),
                MediaType.APPLICATION_PDF,
                "azy-livraison-" + serviceDate + "-rapport-general.pdf");
    }

    @GetMapping(
            value = "/general.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> generalXlsx(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate) {
        return download(
                service.renderGeneralXlsx(serviceDate),
                XLSX_MEDIA_TYPE,
                "azy-livraison-" + serviceDate + "-rapport-general.xlsx");
    }

    @GetMapping(value = "/clients/{accountId}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> clientPdf(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID accountId) {
        return download(
                service.renderClientPdf(serviceDate, accountId),
                MediaType.APPLICATION_PDF,
                "azy-livraison-" + serviceDate + "-client-" + accountId + ".pdf");
    }

    @GetMapping(
            value = "/clients/{accountId}.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> clientXlsx(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID accountId) {
        return download(
                service.renderClientXlsx(serviceDate, accountId),
                XLSX_MEDIA_TYPE,
                "azy-livraison-" + serviceDate + "-client-" + accountId + ".xlsx");
    }

    private ResponseEntity<byte[]> download(byte[] content, MediaType mediaType, String filename) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(content);
    }
}
