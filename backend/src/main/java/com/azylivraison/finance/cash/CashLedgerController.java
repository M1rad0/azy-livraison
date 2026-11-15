package com.azylivraison.finance.cash;

import static com.azylivraison.finance.cash.CashLedgerModels.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.azylivraison.finance.domain.FinanceModels.ProofDownload;

import jakarta.validation.Valid;

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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The existing /api/admin security rule protects every operation and proof. */
@RestController
@RequestMapping("/api/admin/cash-ledger")
public class CashLedgerController {
    private final CashLedgerService service;

    public CashLedgerController(CashLedgerService service) { this.service = service; }

    @GetMapping
    public Snapshot snapshot() { return service.getSnapshot(); }

    @PostMapping(path = "/transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Movement transfer(@Valid @RequestBody TransferRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.confirmTransfer(request, actor(jwt), null);
    }

    @PostMapping(path = "/transfers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Movement transferWithProof(@Valid @RequestPart("transfer") TransferRequest request,
            @RequestPart(value = "proof", required = false) MultipartFile proof, @AuthenticationPrincipal Jwt jwt) {
        return service.confirmTransfer(request, actor(jwt), proof);
    }

    @PostMapping("/adjustments")
    public Movement adjustment(@Valid @RequestBody AdjustmentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.adjust(request, actor(jwt));
    }

    @PostMapping("/movements/{id}/void")
    public Movement voidMovement(@PathVariable UUID id, @Valid @RequestBody VoidRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.voidMovement(id, request, actor(jwt));
    }

    @GetMapping("/movements/{id}/proof")
    public ResponseEntity<byte[]> proof(@PathVariable UUID id) {
        ProofDownload proof = service.getProof(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(proof.contentType()))
                .contentLength(proof.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(proof.originalFilename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0")
                .header("X-Content-Type-Options", "nosniff").body(proof.content());
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("accountId")); }
}
