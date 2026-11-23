package com.azylivraison.finance.cash.briefing;

import static com.azylivraison.finance.cash.briefing.CashBriefingModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CashBriefingApiIntegrationTest {
    private static final UUID ACTOR = UUID.fromString("c8000000-0000-0000-0000-000000000001");
    private static final UUID TRANSFER = UUID.fromString("c8000000-0000-0000-0000-000000000002");
    private static final String PATH = "/api/admin/cash-briefings/courier-transfers/" + TRANSFER + "/confirm";
    @Autowired MockMvc mvc;
    @MockitoBean CashBriefingService service;

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor principal(String role) {
        return jwt().jwt(token -> token.subject("cash-test").claim("accountId", ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
    private Batch result() {
        return new Batch(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 11, 23), "ACTIVE", 8,
                Instant.parse("2026-11-24T00:00:00Z"), "Test", List.of(), List.of(), "Préparation", "MANUAL",
                List.of(new CourierTransfer(TRANSFER, UUID.randomUUID(), "Donneur", UUID.randomUUID(), "Receveur",
                        4000, "CONFIRMED", UUID.randomUUID(), false, null)), 2);
    }

    @Test void handoverConfirmationRequiresAnAdministrator() throws Exception {
        String json = "{\"paymentMethod\":\"MVola\",\"expectedLedgerRevision\":7}";
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(principal("CLIENT")).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void jsonConfirmationRoutesTheActorAndSerializesTheNewWorkflow() throws Exception {
        when(service.confirmCourierTransfer(eq(TRANSFER), any(ConfirmRequest.class), eq(ACTOR), isNull())).thenReturn(result());
        mvc.perform(post(PATH).with(principal("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"MVola\",\"reference\":\"REF\",\"expectedLedgerRevision\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workflowVersion").value(2))
                .andExpect(jsonPath("$.courierTransfers[0].status").value("CONFIRMED"));
        verify(service).confirmCourierTransfer(TRANSFER, new ConfirmRequest("MVola", "REF", null, 7L), ACTOR, null);
    }

    @Test void multipartConfirmationKeepsTheOptionalProof() throws Exception {
        when(service.confirmCourierTransfer(eq(TRANSFER), any(ConfirmRequest.class), eq(ACTOR), any())).thenReturn(result());
        var transfer = new MockMultipartFile("transfer", "", "application/json",
                "{\"paymentMethod\":\"MVola\",\"expectedLedgerRevision\":7}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var proof = new MockMultipartFile("proof", "recu.pdf", "application/pdf", new byte[] {1, 2, 3});
        mvc.perform(multipart(PATH).file(transfer).file(proof).with(principal("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.courierTransfers[0].amountAriary").value(4000));
        verify(service).confirmCourierTransfer(eq(TRANSFER), eq(new ConfirmRequest("MVola", null, null, 7L)), eq(ACTOR),
                argThat(file -> file != null && file.getOriginalFilename().equals("recu.pdf")));
    }

    @Test void courierAmountsCannotSilentlyTruncateDecimalsAtTheHttpBoundary() throws Exception {
        String body = """
                {"planId":"c8000000-0000-0000-0000-000000000010","idempotencyKey":"decimal",
                 "expectedLedgerRevision":7,"transfers":[],"courierTransfers":[{
                 "fromCourierAccountId":"c8000000-0000-0000-0000-000000000011",
                 "toCourierAccountId":"c8000000-0000-0000-0000-000000000012","amountAriary":1.5}]}
                """;
        mvc.perform(post("/api/admin/cash-briefings/manual").with(principal("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
