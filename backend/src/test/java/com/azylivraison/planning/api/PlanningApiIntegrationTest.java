package com.azylivraison.planning.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanningApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validatesAndNormalizesAPlan() throws Exception {
        mockMvc.perform(post("/api/plans/validate").with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readResource("plans/valid-plan.json")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.plan.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.plan.name").value("Tournée Antananarivo matin"))
                .andExpect(jsonPath("$.plan.couriers[0].maxWeightKg").value(35))
                .andExpect(jsonPath("$.plan.couriers[0].averageSpeedKmh").value(22))
                .andExpect(jsonPath("$.plan.couriers[0].departureAt")
                        .value("2026-08-03T07:30:00+03:00"))
                .andExpect(jsonPath("$.plan.couriers[0].departureLocation.destinationQuery")
                        .value("Isoraka, Antananarivo"))
                .andExpect(jsonPath("$.plan.couriers[1].maxWeightKg").value(10))
                .andExpect(jsonPath("$.plan.couriers[1].averageSpeedKmh").value(12))
                .andExpect(jsonPath("$.plan.packages[0].destination.destinationQuery")
                        .value("Analakely, Antananarivo"))
                .andExpect(jsonPath("$.plan.packages[0].deliveryWindow.endAt")
                        .value("2026-08-03T11:00:00+03:00"))
                .andExpect(jsonPath("$.plan.packages[0].destination.latitude").doesNotExist())
                .andExpect(jsonPath("$.plan.packages[1].destination.latitude").value(-18.91))
                .andExpect(jsonPath("$.plan.packages[1].destination.longitude").value(47.525));
    }

    @Test
    void reportsAllDomainValidationErrors() throws Exception {
        String invalidPlan = """
                {
                  "schemaVersion": "0.9",
                  "pattern": "SINGLE_PICKUP_MULTI_DELIVERY",
                  "planId": "plan-001",
                  "name": "Plan métier invalide",
                  "departureAt": "2026-08-03T08:00:00+03:00",
                  "timezone": "Indian/Antananarivo",
                  "settings": { "returnToDepot": false, "routingPreset": "MAIN_ROADS" },
                  "depot": { "latitude": -18.8792 },
                  "couriers": [
                    { "id": "livreur-001", "name": "Un", "vehicleType": "MOTO" },
                    { "id": "livreur-001", "name": "Deux", "vehicleType": "BICYCLE" }
                  ],
                  "packages": [
                    {
                      "id": "colis-001",
                      "label": "Premier",
                      "weightKg": 1,
                      "destination": { "destinationQuery": "Analakely" }
                    },
                    {
                      "id": "colis-001",
                      "label": "Second",
                      "weightKg": 2,
                      "destination": {}
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/plans/validate").with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPlan))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.plan").doesNotExist())
                .andExpect(jsonPath("$.errors[?(@.code == 'UNSUPPORTED_SCHEMA_VERSION')]").exists())
                .andExpect(jsonPath("$.errors[?(@.path == 'depot' && @.code == 'INCOMPLETE_COORDINATES')]")
                        .exists())
                .andExpect(jsonPath("$.errors[?(@.path == 'couriers[1].id' && @.code == 'DUPLICATE_ID')]")
                        .exists())
                .andExpect(jsonPath("$.errors[?(@.path == 'packages[1].id' && @.code == 'DUPLICATE_ID')]")
                        .exists())
                .andExpect(jsonPath("$.errors[?(@.path == 'packages[1].destination' && @.code == 'DESTINATION_REQUIRED')]")
                        .exists());
    }

    @Test
    void reportsBeanValidationErrors() throws Exception {
        String invalidWeight = readResource("plans/valid-plan.json")
                .replace("\"weightKg\": 4.5", "\"weightKg\": 0");

        mockMvc.perform(post("/api/plans/validate").with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidWeight))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[?(@.path == 'packages[0].weightKg')]").exists());
    }

    @Test
    void rejectsAnOffsetThatDoesNotMatchAntananarivo() throws Exception {
        String mismatchedOffset = readResource("plans/valid-plan.json")
                .replace("2026-08-03T08:00:00+03:00", "2026-08-03T08:00:00+02:00");

        mockMvc.perform(post("/api/plans/validate").with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mismatchedOffset))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.code == 'TIMEZONE_OFFSET_MISMATCH')]").exists());
    }

    @Test
    void rejectsAnInvertedDeliveryWindow() throws Exception {
        String invertedWindow = readResource("plans/valid-plan.json")
                .replace("2026-08-03T11:00:00+03:00", "2026-08-03T08:30:00+03:00");

        mockMvc.perform(post("/api/plans/validate").with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invertedWindow))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.path == 'packages[0].deliveryWindow' && @.code == 'INVALID_TIME_WINDOW')]")
                        .exists());
    }

    @Test
    void rejectsUnknownJsonProperties() throws Exception {
        String unknownProperty = readResource("plans/valid-plan.json")
                .replace("\"schemaVersion\": \"1.0\"", "\"unknown\": true, \"schemaVersion\": \"1.0\"");

        mockMvc.perform(post("/api/plans/validate").with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownProperty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("MALFORMED_JSON"));
    }

    @Test
    void acceptsAnOverweightPackageForLaterAssignmentAnalysis() throws Exception {
        String overweightPackage = readResource("plans/valid-plan.json")
                .replace("\"weightKg\": 4.5", "\"weightKg\": 1000");

        mockMvc.perform(post("/api/plans/validate").with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overweightPackage))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.plan.packages[0].weightKg").value(1000));
    }

    @Test
    void validatesAndNormalizesAMultiClientPlan() throws Exception {
        mockMvc.perform(post("/api/plans/validate").with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multiClientPlan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.plan.schemaVersion").value("2.0"))
                .andExpect(jsonPath("$.plan.pattern").value("MULTI_CLIENT_MULTI_PICKUP_DELIVERY"))
                .andExpect(jsonPath("$.plan.depot").doesNotExist())
                .andExpect(jsonPath("$.plan.packages").isEmpty())
                .andExpect(jsonPath("$.plan.clients.length()").value(2))
                .andExpect(jsonPath("$.plan.clients[0].deliveryGroups[0].pickup.label").value("Retrait Alpha"))
                .andExpect(jsonPath("$.plan.clients[1].deliveryGroups[0].packages[0].id").value("colis-beta"));
    }

    @Test
    void listsVehiclePresets() throws Exception {
        mockMvc.perform(get("/api/vehicle-presets").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("MOTO"))
                .andExpect(jsonPath("$[0].maxWeightKg").value(30))
                .andExpect(jsonPath("$[1].type").value("BICYCLE"))
                .andExpect(jsonPath("$[1].averageSpeedKmh").value(12));
    }

    private String readResource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private String multiClientPlan() {
        return """
                {
                  "schemaVersion": "2.0",
                  "pattern": "MULTI_CLIENT_MULTI_PICKUP_DELIVERY",
                  "planId": "multi-001",
                  "name": "Deux clients",
                  "departureAt": "2026-08-05T08:00:00+03:00",
                  "timezone": "Indian/Antananarivo",
                  "settings": { "returnToDepot": false, "routingPreset": "MAIN_ROADS" },
                  "couriers": [{
                    "id": "livreur-001", "name": "Soa", "vehicleType": "MOTO",
                    "maxWeightKg": 10, "averageSpeedKmh": 25
                  }],
                  "clients": [
                    {
                      "id": "client-alpha", "name": "Client Alpha",
                      "deliveryGroups": [{
                        "id": "groupe-alpha", "name": "Course Alpha",
                        "pickup": { "label": "Retrait Alpha", "destinationQuery": "Analakely" },
                        "packages": [{
                          "id": "colis-alpha", "label": "Destinataire Alpha", "weightKg": 2,
                          "destination": { "destinationQuery": "Ankorondrano" }
                        }]
                      }]
                    },
                    {
                      "id": "client-beta", "name": "Client Beta",
                      "deliveryGroups": [{
                        "id": "groupe-beta", "name": "Course Beta",
                        "pickup": { "label": "Retrait Beta", "destinationQuery": "Ivandry" },
                        "packages": [{
                          "id": "colis-beta", "label": "Destinataire Beta", "weightKg": 1,
                          "destination": { "destinationQuery": "Ambodivona" }
                        }]
                      }]
                    }
                  ]
                }
                """;
    }
}
