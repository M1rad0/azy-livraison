package com.azylivraison.delivery.api;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExpressDeliveryApiIntegrationTest {

    private static final UUID CLIENT_ID = UUID.fromString("e1000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ID = UUID.fromString("e1000000-0000-0000-0000-000000000002");
    private static final UUID COURIER_ID = UUID.fromString("e1000000-0000-0000-0000-000000000003");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void installActors() {
        jdbcTemplate.update("""
                INSERT INTO app_account (id, username, password_hash, enabled, role, display_name, phone)
                VALUES (?, 'express-client', 'unused', TRUE, 'CLIENT', 'Client express', '0340000000'),
                       (?, 'express-admin', 'unused', TRUE, 'ADMIN', 'Admin express', '0340000001')
                """, CLIENT_ID, ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO client_profile (account_id, express_delivery_enabled)
                VALUES (?, FALSE)
                """, CLIENT_ID);
        jdbcTemplate.update("""
                INSERT INTO courier_profile (
                    id, name, contact, active, vehicle_type, max_weight_kg_override,
                    default_departure_time, departure_location_label,
                    departure_latitude, departure_longitude
                ) VALUES (?, 'Livreur express', '0320000000', TRUE, 'MOTO', 0.5,
                          '06:00:00', 'Analakely', -18.9089, 47.5212)
                """, COURIER_ID);
    }

    @Test
    void requiresClientPermissionButLetsAdministrationCreateExpressRequests() throws Exception {
        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXPRESS_DELIVERY_FORBIDDEN"));

        mockMvc.perform(post("/api/admin/clients/{clientId}/delivery-requests", CLIENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestKind").value("EXPRESS"))
                .andExpect(jsonPath("$.createdByAdministration").value(true));
    }

    @Test
    void confirmsAnExpressRequestWithOneOrMoreCouriersAndWarnsWithoutBlockingCapacity() throws Exception {
        jdbcTemplate.update("UPDATE client_profile SET express_delivery_enabled = TRUE WHERE account_id = ?", CLIENT_ID);
        MvcResult creation = mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expressRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestKind").value("EXPRESS"))
                .andExpect(jsonPath("$.expressStatus").value("PENDING"))
                .andReturn();
        String requestId = objectMapper.readTree(creation.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/admin/express-deliveries/{id}/confirm", requestId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courierIds":["%s"],"version":0}
                                """.formatted(COURIER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.expressStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.request.packages[0].status").value("DISPATCHED"))
                .andExpect(jsonPath("$.couriers[0].id").value(COURIER_ID.toString()))
                .andExpect(jsonPath("$.capacityWarning").value(true));

        mockMvc.perform(get("/api/admin/express-deliveries").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].request.id").value(requestId));
    }

    @Test
    void exposesDefaultNormalAndExpressPricesWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/public/pricing/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].requestKind").value("NORMAL"))
                .andExpect(jsonPath("$[1].requestKind").value("EXPRESS"));

        mockMvc.perform(post("/api/public/pricing/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKind":"EXPRESS",
                                  "destination":{"label":"Analakely","latitude":-18.9089,"longitude":47.5212}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestKind").value("EXPRESS"))
                .andExpect(jsonPath("$.deliveryFeeAriary").value(3000));
    }

    @Test
    void savesAndAppliesACompleteClientPricingGridClonedFromTheDefaultPolicy() throws Exception {
        mockMvc.perform(put("/api/admin/pricing-policies")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientAccountId":"%s",
                                  "requestKind":"EXPRESS",
                                  "center":{"label":"Analakely","destinationQuery":"Analakely","latitude":-18.9089,"longitude":47.5212},
                                  "defaultWeightKg":1,
                                  "zones":[{"id":"72000000-0000-0000-0000-000000000001","name":"Centre","maxDistanceMeters":5000,"deliveryFeeAriary":3500}],
                                  "weightTiers":[{"maxWeightKg":5,"surchargeAriary":700}],
                                  "timeSlots":[{"label":"Soir","startTime":"18:00","endTime":"19:00","surchargeAriary":900}]
                                }
                                """.formatted(CLIENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSpecific").value(true))
                .andExpect(jsonPath("$.requestKind").value("EXPRESS"))
                .andExpect(jsonPath("$.zones[0].id").value(not("72000000-0000-0000-0000-000000000001")));

        mockMvc.perform(post("/api/admin/clients/{clientId}/delivery-pricing/quote", CLIENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKind":"EXPRESS",
                                  "destination":{"label":"Analakely","latitude":-18.9089,"longitude":47.5212},
                                  "weightKg":2,
                                  "desiredDeliveryTime":"18:30"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseDeliveryFeeAriary").value(3500))
                .andExpect(jsonPath("$.weightSurchargeAriary").value(700))
                .andExpect(jsonPath("$.timeSurchargeAriary").value(900))
                .andExpect(jsonPath("$.deliveryFeeAriary").value(5100));

        mockMvc.perform(post("/api/admin/clients/{clientId}/delivery-pricing/quote", CLIENT_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestKind":"EXPRESS",
                                  "destination":{"label":"Analakely","latitude":-18.9089,"longitude":47.5212},
                                  "weightKg":6
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_PRICING_INVALID"));
    }

    @Test
    void acceptsDesiredTimesOnlyForExpressRequestsAndOnlyInTheFuture() throws Exception {
        jdbcTemplate.update("UPDATE client_profile SET express_delivery_enabled = TRUE WHERE account_id = ?", CLIENT_ID);
        String currentMinute = LocalTime.now(ZoneId.of("Indian/Antananarivo"))
                .truncatedTo(ChronoUnit.MINUTES)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        String withDesiredTime = expressRequestJson().replace(
                "\"weightKg\":1",
                "\"desiredDeliveryTime\":\"" + currentMinute + "\",\"weightKg\":1");

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withDesiredTime))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_PRICING_INVALID"));

        mockMvc.perform(post("/api/client/delivery-requests")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withDesiredTime.replace(
                                "\"requestKind\":\"EXPRESS\"",
                                "\"requestKind\":\"NORMAL\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_PRICING_INVALID"));
    }

    private String expressRequestJson() {
        String today = LocalDate.now(ZoneId.of("Indian/Antananarivo")).toString();
        return """
                {
                  "requestedDate":"%s",
                  "requestKind":"EXPRESS",
                  "pickup":{"label":"Boutique","latitude":-18.9089,"longitude":47.5212},
                  "packages":[{
                    "recipientName":"Destinataire",
                    "faritra":false,
                    "destination":{"label":"Ampasampito","latitude":-18.8957,"longitude":47.5458},
                    "contact":"0320000002",
                    "amountToCollectAriary":20000,
                    "mobileMoneyPaid":false,
                    "weightKg":1
                  }]
                }
                """.formatted(today);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor client() {
        return jwt().jwt(token -> token.subject("express-client")
                        .claim("accountId", CLIENT_ID.toString()).claim("role", "CLIENT"))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("express-admin")
                        .claim("accountId", ADMIN_ID.toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
