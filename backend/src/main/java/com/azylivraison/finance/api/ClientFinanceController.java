package com.azylivraison.finance.api;

import static com.azylivraison.finance.api.FinanceApiModels.*;
import static com.azylivraison.finance.domain.FinanceModels.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.azylivraison.finance.service.FinancialSettlementService;

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
@RequestMapping("/api/client/finance")
public class ClientFinanceController {

    private final FinancialSettlementService service;

    public ClientFinanceController(FinancialSettlementService service) {
        this.service = service;
    }

    @GetMapping
    public ClientFinanceResponse finance(@AuthenticationPrincipal Jwt jwt) {
        return ClientFinanceResponse.from(service.getClientFinance(accountId(jwt)));
    }

    @GetMapping("/payments/{paymentId}/proof")
    public ResponseEntity<byte[]> paymentProof(
            @PathVariable UUID paymentId,
            @AuthenticationPrincipal Jwt jwt) {
        ProofDownload proof = service.getClientPaymentProof(paymentId, accountId(jwt));
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

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("accountId"));
    }
}
