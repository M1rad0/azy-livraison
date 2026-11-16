package com.azylivraison.reporting.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.reporting.service.DailyReportService;
import com.azylivraison.reporting.service.DailyReportService.ExportFormat;
import com.azylivraison.reporting.render.ReportDownload;

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
        return download(service.downloadGeneral(serviceDate, ExportFormat.PDF));
    }

    @GetMapping(
            value = "/general.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> generalXlsx(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate) {
        return download(service.downloadGeneral(serviceDate, ExportFormat.XLSX));
    }

    @GetMapping("/general.image")
    public ResponseEntity<byte[]> generalImage(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate) {
        return download(service.downloadGeneral(serviceDate, ExportFormat.IMAGE));
    }

    @GetMapping(value = "/clients/{accountId}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> clientPdf(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID accountId) {
        return download(service.downloadClient(serviceDate, accountId, ExportFormat.PDF));
    }

    @GetMapping(
            value = "/clients/{accountId}.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> clientXlsx(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID accountId) {
        return download(service.downloadClient(serviceDate, accountId, ExportFormat.XLSX));
    }

    @GetMapping("/clients/{accountId}.image")
    public ResponseEntity<byte[]> clientImage(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID accountId) {
        return download(service.downloadClient(serviceDate, accountId, ExportFormat.IMAGE));
    }

    private ResponseEntity<byte[]> download(ReportDownload report) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(report.mediaType()))
                .contentLength(report.content().length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(report.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(report.content());
    }
}
