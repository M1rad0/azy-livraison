package com.azylivraison.finance.api;

import static com.azylivraison.finance.api.FinanceApiModels.*;
import static com.azylivraison.finance.domain.FinanceModels.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azylivraison.finance.service.FinancialSettlementService;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/finance")
public class AdminFinanceController {

    static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final FinancialSettlementService service;

    public AdminFinanceController(FinancialSettlementService service) {
        this.service = service;
    }

    @GetMapping("/clients/{accountId}")
    public ClientFinanceResponse clientFinance(@PathVariable UUID accountId) {
        ClientFinance finance = service.getClientFinance(accountId);
        return ClientFinanceResponse.from(finance, service.cashManagedSettlementIds(finance));
    }

    @PostMapping(
            path = "/client-settlements/{settlementId}/payments",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ClientPaymentResponse createClientPaymentWithoutProof(
            @PathVariable UUID settlementId,
            @Valid @RequestBody CreateClientPaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return createClientPayment(settlementId, request, null, jwt);
    }

    @PostMapping(
            path = "/client-settlements/{settlementId}/payments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ClientPaymentResponse createClientPaymentWithProof(
            @PathVariable UUID settlementId,
            @Valid @RequestPart("payment") CreateClientPaymentRequest request,
            @RequestPart(value = "proof", required = false) MultipartFile proof,
            @AuthenticationPrincipal Jwt jwt) {
        return createClientPayment(settlementId, request, proof, jwt);
    }

    @PostMapping("/client-payments/{paymentId}/void")
    public ClientPaymentResponse voidClientPayment(
            @PathVariable UUID paymentId,
            @Valid @RequestBody VoidPaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ClientPaymentResponse.from(service.voidClientPayment(
                paymentId, request.version(), request.reason(), accountId(jwt)));
    }

    @GetMapping("/client-payments/{paymentId}/proof")
    public ResponseEntity<byte[]> clientPaymentProof(@PathVariable UUID paymentId) {
        return proof(service.getClientPaymentProofForAdmin(paymentId));
    }

    @GetMapping("/delivery-days/{serviceDate}/courier-compensations")
    public CourierCompensationResponse previewCompensations(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate) {
        return CourierCompensationResponse.from(service.previewCompensations(serviceDate), service.legacyAccrualsEnabled());
    }

    @GetMapping("/delivery-days/{serviceDate}/clients/{clientAccountId}/courier-compensations")
    public CourierCompensationResponse previewClientCompensations(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID clientAccountId) {
        return CourierCompensationResponse.from(
                service.previewCompensations(serviceDate, clientAccountId), service.legacyAccrualsEnabled());
    }

    @PostMapping("/delivery-days/{serviceDate}/courier-compensations/finalize")
    public CourierCompensationResponse finalizeCompensations(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @Valid @RequestBody FinalizeCompensationsRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        List<CompensationOverride> overrides = request.overrides() == null
                ? List.of()
                : request.overrides().stream().map(CompensationOverrideRequest::toDomain).toList();
        return CourierCompensationResponse.from(service.finalizeCompensations(
                serviceDate, request.closureRevision(), overrides, accountId(jwt)), service.legacyAccrualsEnabled());
    }

    @PostMapping("/delivery-days/{serviceDate}/clients/{clientAccountId}/courier-compensations/finalize")
    public CourierCompensationResponse finalizeClientCompensations(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @PathVariable UUID clientAccountId,
            @Valid @RequestBody FinalizeCompensationsRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        List<CompensationOverride> overrides = request.overrides() == null
                ? List.of()
                : request.overrides().stream().map(CompensationOverrideRequest::toDomain).toList();
        return CourierCompensationResponse.from(service.finalizeCompensations(
                serviceDate, clientAccountId, request.closureRevision(), overrides, accountId(jwt)), service.legacyAccrualsEnabled());
    }

    @GetMapping("/couriers/{courierId}")
    public CourierFinanceResponse courierFinance(
            @PathVariable UUID courierId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return CourierFinanceResponse.from(service.getCourierFinance(courierId, from, to));
    }

    @GetMapping(value = "/couriers/{courierId}/statement.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> courierStatementPdf(
            @PathVariable UUID courierId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return download(
                service.renderCourierStatementPdf(courierId, from, to),
                MediaType.APPLICATION_PDF,
                "azy-livraison-livreur-" + courierId + "-releve.pdf");
    }

    @GetMapping(
            value = "/couriers/{courierId}/statement.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> courierStatementXlsx(
            @PathVariable UUID courierId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return download(
                service.renderCourierStatementXlsx(courierId, from, to),
                XLSX_MEDIA_TYPE,
                "azy-livraison-livreur-" + courierId + "-releve.xlsx");
    }

    @PostMapping(
            path = "/couriers/{courierId}/payments",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public CourierPaymentResponse createCourierPaymentWithoutProof(
            @PathVariable UUID courierId,
            @Valid @RequestBody CreateCourierPaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return createCourierPayment(courierId, request, null, jwt);
    }

    @PostMapping(
            path = "/couriers/{courierId}/payments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CourierPaymentResponse createCourierPaymentWithProof(
            @PathVariable UUID courierId,
            @Valid @RequestPart("payment") CreateCourierPaymentRequest request,
            @RequestPart(value = "proof", required = false) MultipartFile proof,
            @AuthenticationPrincipal Jwt jwt) {
        return createCourierPayment(courierId, request, proof, jwt);
    }

    @PostMapping("/courier-payments/{paymentId}/void")
    public CourierPaymentResponse voidCourierPayment(
            @PathVariable UUID paymentId,
            @Valid @RequestBody VoidPaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return CourierPaymentResponse.from(service.voidCourierPayment(
                paymentId, request.version(), request.reason(), accountId(jwt)));
    }

    @GetMapping("/courier-payments/{paymentId}/proof")
    public ResponseEntity<byte[]> courierPaymentProof(@PathVariable UUID paymentId) {
        return proof(service.getCourierPaymentProof(paymentId));
    }

    private ClientPaymentResponse createClientPayment(
            UUID settlementId,
            CreateClientPaymentRequest request,
            MultipartFile proof,
            Jwt jwt) {
        return ClientPaymentResponse.from(service.createClientPayment(
                settlementId, request.direction(), request.amountAriary(), request.paymentMethod(),
                request.destination() == null ? null : request.destination().toDomain(),
                request.transactionReference(), request.note(), request.confirmedAt(), accountId(jwt), proof));
    }

    private CourierPaymentResponse createCourierPayment(
            UUID courierId,
            CreateCourierPaymentRequest request,
            MultipartFile proof,
            Jwt jwt) {
        return CourierPaymentResponse.from(service.createCourierPayment(
                courierId, request.amountAriary(), request.paymentMethod(),
                request.destination() == null ? null : request.destination().toDomain(),
                request.transactionReference(), request.note(), request.confirmedAt(), accountId(jwt), proof));
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }

    private ResponseEntity<byte[]> proof(ProofDownload proof) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(proof.contentType()))
                .contentLength(proof.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(proof.originalFilename(), StandardCharsets.UTF_8)
                        .build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0")
                .header("X-Content-Type-Options", "nosniff")
                .body(proof.content());
    }

    private ResponseEntity<byte[]> download(byte[] content, MediaType contentType, String filename) {
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0")
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }
}
