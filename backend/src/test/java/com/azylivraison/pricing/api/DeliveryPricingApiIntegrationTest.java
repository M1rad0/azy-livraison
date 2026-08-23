package com.azylivraison.pricing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DeliveryPricingApiIntegrationTest {

    private static final UUID CLIENT_ID = UUID.fromString("82000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createClient() {
        jdbcTemplate.update("""
                INSERT INTO app_account (
                    id, username, password_hash, enabled, role, display_name, phone
                ) VALUES (?, 'pricing-client', 'unused', TRUE, 'CLIENT', 'Client tarif', '0340000000')
                """, CLIENT_ID);
    }

    @Test
    void exposesAndUpdatesTheOrderedPricingPolicyWithOptimisticLocking() throws Exception {
        mockMvc.perform(get("/api/admin/settings/pricing").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.center.label").value("Antsahavola–Antaninarenina"))
                .andExpect(jsonPath("$.zones.length()").value(5))
                .andExpect(jsonPath("$.zones[0].name").value("Centre-ville"))
                .andExpect(jsonPath("$.zones[0].deliveryFeeAriary").value(3_000))
                .andExpect(jsonPath("$.zones[4].deliveryFeeAriary").value(8_000));

        String update = """
                {
                  "version": 0,
                  "center": {
                    "label": "  Nouveau centre  ",
                    "destinationQuery": "Antananarivo",
                    "latitude": -18.91,
                    "longitude": 47.52
                  },
                  "zones": [
                    {"name":"Centre", "maxDistanceMeters":2500, "deliveryFeeAriary":3000},
                    {"name":"Périphérie", "maxDistanceMeters":7000, "deliveryFeeAriary":4500}
                  ]
                }
                """;
        mockMvc.perform(put("/api/admin/settings/pricing").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.center.label").value("Nouveau centre"))
                .andExpect(jsonPath("$.zones.length()").value(2))
                .andExpect(jsonPath("$.zones[0].id").isNotEmpty());

        mockMvc.perform(put("/api/admin/settings/pricing").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICING_SETTINGS_VERSION_CONFLICT"));
    }

    @Test
    void validatesTheBusinessOrderingOfPricingZones() throws Exception {
        mockMvc.perform(put("/api/admin/settings/pricing").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": 0,
                                  "center": {"label":"Centre", "latitude":-18.91, "longitude":47.52},
                                  "zones": [
                                    {"name":"Même zone", "maxDistanceMeters":5000, "deliveryFeeAriary":5000},
                                    {"name":" même ZONE ", "maxDistanceMeters":4000, "deliveryFeeAriary":4000}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRICING_SETTINGS_INVALID"));
    }

    @Test
    void rejectsANullPricingZoneAsAValidationError() throws Exception {
        mockMvc.perform(put("/api/admin/settings/pricing").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": 0,
                                  "center": {"label":"Centre", "latitude":-18.91, "longitude":47.52},
                                  "zones": [null]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void quotesCoveredDestinationsAndExplicitlyRejectsUncoveredOnes() throws Exception {
        mockMvc.perform(post("/api/client/delivery-pricing/quote").with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"destination": {
                                  "label":"Analakely", "query":"Analakely",
                                  "latitude":-18.9089, "longitude":47.5212
                                }}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricingZoneId").value("71000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.pricingZoneName").value("Centre-ville"))
                .andExpect(jsonPath("$.deliveryFeeAriary").value(3_000))
                .andExpect(jsonPath("$.distanceFromCenterMeters").value(264))
                .andExpect(jsonPath("$.pricingZoneMaxDistanceMeters").value(2_500))
                .andExpect(jsonPath("$.pricingPolicyVersion").value(0));

        mockMvc.perform(post("/api/client/delivery-pricing/quote").with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"destination": {
                                  "label":"Hors zone", "latitude":0, "longitude":0
                                }}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DESTINATION_OUTSIDE_PRICING_ZONES"));
    }

    @Test
    void snapshotsTheQuoteWhenANewPackageIsCreated() throws Exception {
        mockMvc.perform(post("/api/client/delivery-requests").with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deliveryRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packages[0].pricingZoneId")
                        .value("71000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.packages[0].pricingZoneName").value("Centre-ville"))
                .andExpect(jsonPath("$.packages[0].deliveryFeeAriary").value(3_000))
                .andExpect(jsonPath("$.packages[0].pricingPolicyVersion").value(0));

        Long savedFee = jdbcTemplate.queryForObject(
                "SELECT delivery_fee_ariary FROM delivery_package WHERE recipient_name = 'Destinataire tarif'",
                Long.class);
        assertThat(savedFee).isEqualTo(3_000L);
    }

    @Test
    void keepsTheRecordedPackagePriceAfterThePolicyChanges() throws Exception {
        mockMvc.perform(post("/api/client/delivery-requests").with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deliveryRequest()))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/admin/settings/pricing").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": 0,
                                  "center": {
                                    "label":"Antsahavola–Antaninarenina",
                                    "destinationQuery":"Antsahavola, Antaninarenina, Antananarivo, Madagascar",
                                    "latitude":-18.9096,
                                    "longitude":47.5236
                                  },
                                  "zones": [
                                    {"name":"Nouveau tarif", "maxDistanceMeters":16500, "deliveryFeeAriary":9000}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/client/delivery-requests").with(client()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].packages[0].pricingZoneName").value("Centre-ville"))
                .andExpect(jsonPath("$[0].packages[0].deliveryFeeAriary").value(3_000))
                .andExpect(jsonPath("$[0].packages[0].pricingPolicyVersion").value(0));
    }

    @Test
    void rejectsTheWholeRequestWhenOnePackageIsOutsideCoverage() throws Exception {
        mockMvc.perform(post("/api/client/delivery-requests").with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedDate":"%s",
                                  "pickup":{"label":"Retrait", "latitude":-18.91, "longitude":47.52},
                                  "packages":[
                                    {
                                      "recipientName":"Dans la zone", "faritra":false,
                                      "destination":{"label":"Analakely", "latitude":-18.9089, "longitude":47.5212},
                                      "contact":"0340000001", "amountToCollectAriary":10000,
                                      "mobileMoneyPaid":false
                                    },
                                    {
                                      "recipientName":"Hors couverture", "faritra":false,
                                      "destination":{"label":"Hors zone", "latitude":0, "longitude":0},
                                      "contact":"0340000002", "amountToCollectAriary":12000,
                                      "mobileMoneyPaid":false
                                    }
                                  ]
                                }
                                """.formatted(LocalDate.now().plusDays(1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DESTINATION_OUTSIDE_PRICING_ZONES"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_request WHERE owner_account_id = ?",
                Integer.class, CLIENT_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_package", Integer.class)).isZero();
    }

    private String deliveryRequest() {
        return """
                {
                  "requestedDate":"%s",
                  "pickup":{"label":"Retrait", "latitude":-18.91, "longitude":47.52},
                  "packages":[{
                    "recipientName":"Destinataire tarif",
                    "faritra":false,
                    "destination":{"label":"Analakely", "latitude":-18.9089, "longitude":47.5212},
                    "contact":"0340000001",
                    "amountToCollectAriary":10000,
                    "mobileMoneyPaid":false
                  }]
                }
                """.formatted(LocalDate.now().plusDays(1));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor client() {
        return jwt()
                .jwt(token -> token.subject("client")
                        .claim("accountId", CLIENT_ID.toString()).claim("role", "CLIENT"))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt()
                .jwt(token -> token.subject("admin")
                        .claim("accountId", UUID.randomUUID().toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
