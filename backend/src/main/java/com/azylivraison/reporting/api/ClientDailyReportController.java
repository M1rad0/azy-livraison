package com.azylivraison.reporting.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.reporting.domain.ClientDailyReport;
import com.azylivraison.reporting.service.DailyReportService;
import com.azylivraison.reporting.service.DailyReportService.ClientReportSummary;
import com.azylivraison.reporting.service.DailyReportService.ExportFormat;
import com.azylivraison.reporting.render.ReportDownload;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/reports")
public class ClientDailyReportController {

    private final DailyReportService service;

    public ClientDailyReportController(DailyReportService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClientReportSummary> list(@AuthenticationPrincipal Jwt jwt) {
        return service.listClientReports(accountId(jwt));
    }

    @GetMapping("/{serviceDate}")
    public ClientDailyReport get(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @AuthenticationPrincipal Jwt jwt) {
        return service.getClientReport(serviceDate, accountId(jwt));
    }

    @GetMapping(value = "/{serviceDate}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @AuthenticationPrincipal Jwt jwt) {
        return download(service.downloadClient(serviceDate, accountId(jwt), ExportFormat.PDF));
    }

    @GetMapping(
            value = "/{serviceDate}.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> xlsx(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @AuthenticationPrincipal Jwt jwt) {
        return download(service.downloadClient(serviceDate, accountId(jwt), ExportFormat.XLSX));
    }

    @GetMapping("/{serviceDate}.image")
    public ResponseEntity<byte[]> image(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @AuthenticationPrincipal Jwt jwt) {
        return download(service.downloadClient(serviceDate, accountId(jwt), ExportFormat.IMAGE));
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
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
