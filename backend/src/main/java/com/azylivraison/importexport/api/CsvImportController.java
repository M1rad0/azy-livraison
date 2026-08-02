package com.azylivraison.importexport.api;

import java.nio.charset.StandardCharsets;

import com.azylivraison.importexport.service.CsvImportResult;
import com.azylivraison.importexport.service.CsvImportService;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/import")
public class CsvImportController {

    private static final MediaType CSV_MEDIA_TYPE = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final CsvImportService csvImportService;

    public CsvImportController(CsvImportService csvImportService) {
        this.csvImportService = csvImportService;
    }

    @PostMapping(value = "/packages/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CsvImportResponse<DeliveryPackage> importPackages(@RequestPart("file") MultipartFile file) {
        CsvImportResult<DeliveryPackage> result = csvImportService.importPackages(file);
        return CsvImportResponse.success(
                result.importType(), result.filename(), result.delimiter(), result.items());
    }

    @PostMapping(value = "/couriers/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CsvImportResponse<Courier> importCouriers(@RequestPart("file") MultipartFile file) {
        CsvImportResult<Courier> result = csvImportService.importCouriers(file);
        return CsvImportResponse.success(
                result.importType(), result.filename(), result.delimiter(), result.items());
    }

    @GetMapping(value = "/packages/template", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> packagesTemplate() {
        return template("packages-template.csv", CsvTemplates.PACKAGES);
    }

    @GetMapping(value = "/couriers/template", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> couriersTemplate() {
        return template("couriers-template.csv", CsvTemplates.COURIERS);
    }

    private ResponseEntity<byte[]> template(String filename, String content) {
        return ResponseEntity.ok()
                .contentType(CSV_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(content.getBytes(StandardCharsets.UTF_8));
    }
}
