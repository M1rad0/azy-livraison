package com.azylivraison.pricing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdvancedPricingApiIntegrationTest {

    private static final UUID CLIENT_ID = UUID.fromString("82000000-0000-0000-0000-000000000021");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createClient() {
        jdbcTemplate.update("""
                INSERT INTO app_account (id, username, password_hash, enabled, role, display_name, phone)
                VALUES (?, 'advanced-pricing-client', 'unused', TRUE, 'CLIENT', 'Client tarifs', '0340000021')
                """, CLIENT_ID);
    }

    @Test
    void migrationDefaultsAllPoliciesToFiveHundredAndEmptyKeywordLists() throws Exception {
        mockMvc.perform(get("/api/public/pricing/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].morningSurchargeAriary").value(500))
                .andExpect(jsonPath("$[0].afternoonSurchargeAriary").value(500))
                .andExpect(jsonPath("$[0].keywordRules.length()").value(0))
                .andExpect(jsonPath("$[1].morningSurchargeAriary").value(500))
                .andExpect(jsonPath("$[1].afternoonSurchargeAriary").value(500));
    }

    @Test
    void savesPeriodAmountsAndOrderedKeywordRulesAndUsesThemInPublicQuotes() throws Exception {
        savePolicy(null, "NORMAL", 600, 800, """
                [{"keyword":"ecole","deliveryFeeAriary":1000},
                 {"keyword":"école centrale","deliveryFeeAriary":1500},
                 {"keyword":"ECOLE CENTRALE","deliveryFeeAriary":9000}]
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.morningSurchargeAriary").value(600))
                .andExpect(jsonPath("$.afternoonSurchargeAriary").value(800))
                .andExpect(jsonPath("$.keywordRules[1].id").isNotEmpty())
                .andExpect(jsonPath("$.keywordRules[1].keyword").value("école centrale"));

        mockMvc.perform(post("/api/public/pricing/quote").contentType(MediaType.APPLICATION_JSON)
                        .content(quoteRequest("NORMAL", "MORNING", "Bureau", "12 route ECOLE CENTRALE", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseDeliveryFeeAriary").value(1500))
                .andExpect(jsonPath("$.weightSurchargeAriary").value(200))
                .andExpect(jsonPath("$.timeSurchargeAriary").value(600))
                .andExpect(jsonPath("$.deliveryFeeAriary").value(2300))
                .andExpect(jsonPath("$.matchedKeyword").value("école centrale"))
                .andExpect(jsonPath("$.deliveryTimePreference").value("MORNING"))
                .andExpect(jsonPath("$.desiredDeliveryTime").value(startsWith("00:00")))
                .andExpect(jsonPath("$.desiredDeliveryEndTime").value(startsWith("12:00")));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM delivery_pricing_keyword_rule WHERE pricing_policy_id = 1
                """, Integer.class)).isEqualTo(3);

        savePolicy(null, "NORMAL", 600, 800, "[]")
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM delivery_pricing_keyword_rule WHERE pricing_policy_id = 1
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void keepsClientAndNormalExpressRulesIndependent() throws Exception {
        savePolicy(null, "NORMAL", 500, 500, "[]").andExpect(status().isOk());
        savePolicy(CLIENT_ID, "NORMAL", 1100, 1200,
                "[{\"keyword\":\"ecole\",\"deliveryFeeAriary\":7000}]")
                .andExpect(status().isOk());
        savePolicy(CLIENT_ID, "EXPRESS", 2100, 2200,
                "[{\"keyword\":\"ecole\",\"deliveryFeeAriary\":8000}]")
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/client/delivery-pricing/quote").with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quoteRequest("NORMAL", "AFTERNOON", "ÉCOLE", "adresse", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseDeliveryFeeAriary").value(7000))
                .andExpect(jsonPath("$.timeSurchargeAriary").value(1200))
                .andExpect(jsonPath("$.deliveryFeeAriary").value(8400));
        mockMvc.perform(post("/api/client/delivery-pricing/quote").with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quoteRequest("EXPRESS", "AFTERNOON", "ÉCOLE", "adresse", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseDeliveryFeeAriary").value(8000))
                .andExpect(jsonPath("$.timeSurchargeAriary").value(2200))
                .andExpect(jsonPath("$.deliveryFeeAriary").value(10400));
        mockMvc.perform(post("/api/public/pricing/quote").contentType(MediaType.APPLICATION_JSON)
                        .content(quoteRequest("NORMAL", "AFTERNOON", "ÉCOLE", "adresse", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseDeliveryFeeAriary").value(3000))
                .andExpect(jsonPath("$.timeSurchargeAriary").value(500));
    }

    @Test
    void customQuoteKeepsFreeEndTimeAndDoesNotAddPeriodSurcharge() throws Exception {
        savePolicy(null, "NORMAL", 600, 800, "[]").andExpect(status().isOk());
        mockMvc.perform(post("/api/public/pricing/quote").contentType(MediaType.APPLICATION_JSON)
                        .content(quoteRequest("NORMAL", "CUSTOM", "Bureau", "adresse", """
                                ,"desiredDeliveryTime":"11:30:00","desiredDeliveryEndTime":"15:45:00"
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desiredDeliveryEndTime").value(startsWith("15:45")))
                .andExpect(jsonPath("$.timeSurchargeAriary").value(900))
                .andExpect(jsonPath("$.deliveryFeeAriary").value(4100));

        mockMvc.perform(post("/api/public/pricing/quote").contentType(MediaType.APPLICATION_JSON)
                        .content(quoteRequest("NORMAL", "MORNING", "Bureau", "adresse", """
                                ,"desiredDeliveryTime":"11:30:00","desiredDeliveryEndTime":"15:45:00"
                                """)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validatesNegativeSupplementsAndBlankKeywordRules() throws Exception {
        savePolicy(null, "NORMAL", -1, 500, "[]").andExpect(status().isBadRequest());
        savePolicy(null, "NORMAL", 500, 500, "[{\"keyword\":\"   \",\"deliveryFeeAriary\":1000}]")
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions savePolicy(UUID clientId, String kind,
            long morning, long afternoon, String keywords) throws Exception {
        return mockMvc.perform(put("/api/admin/pricing-policies").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clientAccountId":%s,"requestKind":"%s","version":0,
                         "center":{"label":"Centre","latitude":0,"longitude":0},
                         "defaultWeightKg":1,
                         "zones":[{"name":"Centre","maxDistanceMeters":2000,"deliveryFeeAriary":3000}],
                         "weightTiers":[{"maxWeightKg":10,"surchargeAriary":200}],
                         "timeSlots":[{"label":"Heure exacte","startTime":"00:00:00","endTime":"23:59:59","surchargeAriary":900}],
                         "morningSurchargeAriary":%s,"afternoonSurchargeAriary":%s,"keywordRules":%s}
                        """.formatted(clientId == null ? "null" : "\"" + clientId + "\"",
                        kind, morning, afternoon, keywords)));
    }

    private String quoteRequest(String kind, String preference, String label, String query, String extraFields) {
        return """
                {"requestKind":"%s","deliveryTimePreference":"%s",
                 "destination":{"label":"%s","query":"%s","latitude":0,"longitude":0}%s}
                """.formatted(kind, preference, label, query, extraFields);
    }

    private RequestPostProcessor client() {
        return jwt().jwt(token -> token.subject("client").claim("accountId", CLIENT_ID.toString()).claim("role", "CLIENT"))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin").claim("accountId", UUID.randomUUID().toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
