package com.azylivraison.finance.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import com.azylivraison.finance.service.FinancialSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourierStatementApiIntegrationTest {

    private static final UUID COURIER_ID = UUID.fromString("b1000000-0000-0000-0000-000000000001");
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 21);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FinancialSettlementService service;

    @Test
    void administratorDownloadsPdfAndXlsxWithTheSameDateFiltersAsTheFinanceView() throws Exception {
        byte[] pdf = "%PDF-statement".getBytes(StandardCharsets.US_ASCII);
        byte[] xlsx = new byte[] {'P', 'K', 3, 4};
        when(service.renderCourierStatementPdf(COURIER_ID, FROM, TO)).thenReturn(pdf);
        when(service.renderCourierStatementXlsx(COURIER_ID, FROM, TO)).thenReturn(xlsx);

        mockMvc.perform(get("/api/admin/finance/couriers/{courierId}/statement.pdf", COURIER_ID)
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(pdf))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("azy-livraison-livreur-" + COURIER_ID + "-releve.pdf")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        mockMvc.perform(get("/api/admin/finance/couriers/{courierId}/statement.xlsx", COURIER_ID)
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(AdminFinanceController.XLSX_MEDIA_TYPE))
                .andExpect(content().bytes(xlsx))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("azy-livraison-livreur-" + COURIER_ID + "-releve.xlsx")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0"));

        verify(service).renderCourierStatementPdf(COURIER_ID, FROM, TO);
        verify(service).renderCourierStatementXlsx(COURIER_ID, FROM, TO);
    }

    @Test
    void courierStatementsRemainRestrictedToAdministrators() throws Exception {
        mockMvc.perform(get("/api/admin/finance/couriers/{courierId}/statement.pdf", COURIER_ID)
                        .with(client()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/finance/couriers/{courierId}/statement.xlsx", COURIER_ID)
                        .with(client()))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(builder -> builder.claim("accountId", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor client() {
        return jwt().jwt(builder -> builder.claim("accountId", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }
}
