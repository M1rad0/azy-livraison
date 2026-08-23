package com.azylivraison.reporting.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.reporting.domain.DailyReport;
import com.azylivraison.reporting.service.DailyReportService;
import com.azylivraison.reporting.service.DailyReportService.ClientReportSummary;

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
    public DailyReport get(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @AuthenticationPrincipal Jwt jwt) {
        return service.getClientReport(serviceDate, accountId(jwt));
    }

    @GetMapping(value = "/{serviceDate}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @AuthenticationPrincipal Jwt jwt) {
        return download(
                service.renderClientPdf(serviceDate, accountId(jwt)),
                MediaType.APPLICATION_PDF,
                "azy-livraison-" + serviceDate + "-rapport-client.pdf");
    }

    @GetMapping(
            value = "/{serviceDate}.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> xlsx(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @AuthenticationPrincipal Jwt jwt) {
        return download(
                service.renderClientXlsx(serviceDate, accountId(jwt)),
                AdminDailyReportController.XLSX_MEDIA_TYPE,
                "azy-livraison-" + serviceDate + "-rapport-client.xlsx");
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
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
