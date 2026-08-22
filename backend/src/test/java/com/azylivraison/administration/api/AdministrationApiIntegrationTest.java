package com.azylivraison.administration.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdministrationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exposesTheOperationalDefaults() throws Exception {
        mockMvc.perform(get("/api/admin/settings").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyPackageLimit").value(100))
                .andExpect(jsonPath("$.defaultHub").doesNotExist())
                .andExpect(jsonPath("$.defaultPackageWeightKg").value(1))
                .andExpect(jsonPath("$.pickupServiceMinutes").value(10))
                .andExpect(jsonPath("$.hubServiceMinutes").value(15))
                .andExpect(jsonPath("$.deliveryServiceMinutes").value(5))
                .andExpect(jsonPath("$.earlyToleranceMinutes").value(0))
                .andExpect(jsonPath("$.lateToleranceMinutes").value(30))
                .andExpect(jsonPath("$.optimizationTimeLimitSeconds").value(5))
                .andExpect(jsonPath("$.routingPreset").value("MAIN_ROADS"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void updatesSettingsAndKeepsTheConfirmedHubDetails() throws Exception {
        mockMvc.perform(put("/api/admin/settings").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dailyPackageLimit": 75,
                                  "defaultHub": {
                                    "label": "Point de ralliement Antanimena",
                                    "destinationQuery": "Antanimena, Antananarivo",
                                    "latitude": -18.8952,
                                    "longitude": 47.5183
                                  },
                                  "defaultPackageWeightKg": 1.5,
                                  "pickupServiceMinutes": 8,
                                  "hubServiceMinutes": 12,
                                  "deliveryServiceMinutes": 4,
                                  "earlyToleranceMinutes": 0,
                                  "lateToleranceMinutes": 25,
                                  "optimizationTimeLimitSeconds": 8,
                                  "routingPreset": "MAIN_ROADS",
                                  "version": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyPackageLimit").value(75))
                .andExpect(jsonPath("$.defaultHub.label").value("Point de ralliement Antanimena"))
                .andExpect(jsonPath("$.defaultHub.destinationQuery").value("Antanimena, Antananarivo"))
                .andExpect(jsonPath("$.defaultHub.latitude").value(-18.8952))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void requiresConfirmedCoordinatesWhenAHubIsProvided() throws Exception {
        mockMvc.perform(put("/api/admin/settings").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dailyPackageLimit": 100,
                                  "defaultHub": { "label": "Antanimena" },
                                  "defaultPackageWeightKg": 1,
                                  "pickupServiceMinutes": 10,
                                  "hubServiceMinutes": 15,
                                  "deliveryServiceMinutes": 5,
                                  "earlyToleranceMinutes": 0,
                                  "lateToleranceMinutes": 30,
                                  "optimizationTimeLimitSeconds": 5,
                                  "routingPreset": "MAIN_ROADS",
                                  "version": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Validation impossible."))
                .andExpect(jsonPath("$.errors[?(@.field == 'defaultHub.latitude')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'defaultHub.longitude')]").exists());
    }

    @Test
    void updatesVehiclePresetsAndRejectsStaleVersions() throws Exception {
        mockMvc.perform(put("/api/admin/vehicle-presets/MOTO").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxWeightKg":35,"averageSpeedKmh":27,"version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleType").value("MOTO"))
                .andExpect(jsonPath("$.maxWeightKg").value(35))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(put("/api/admin/vehicle-presets/MOTO").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxWeightKg":40,"averageSpeedKmh":28,"version":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VEHICLE_PRESET_VERSION_CONFLICT"));
    }

    @Test
    void updatesVehiclePresetsAsACollectionFromTheSettingsEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/settings/presets").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vehicleType").value("MOTO"))
                .andExpect(jsonPath("$[1].vehicleType").value("BICYCLE"));

        mockMvc.perform(put("/api/admin/settings/presets").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {
                                    "vehicleType":"MOTO",
                                    "maxWeightKg":32,
                                    "averageSpeedKmh":24,
                                    "version":0
                                  },
                                  {
                                    "vehicleType":"BICYCLE",
                                    "maxWeightKg":11,
                                    "averageSpeedKmh":13,
                                    "version":0
                                  }
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].maxWeightKg").value(32))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[1].averageSpeedKmh").value(13))
                .andExpect(jsonPath("$[1].version").value(1));
    }

    @Test
    void createsUpdatesAndDisablesACourierWithEffectivePresetValues() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/admin/couriers").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "  Tiana  ",
                                  "contact": "034 00 000 00",
                                  "vehicleType": "MOTO",
                                  "maxWeightKg": null,
                                  "averageSpeedKmh": null,
                                  "defaultDepartureTime": "06:30:00",
                                  "departureLocation": {
                                    "label": "Alasora",
                                    "destinationQuery": "Alasora, Madagascar",
                                    "latitude": -18.953,
                                    "longitude": 47.568
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.name").value("Tiana"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.maxWeightKg").doesNotExist())
                .andExpect(jsonPath("$.averageSpeedKmh").doesNotExist())
                .andExpect(jsonPath("$.effectiveMaxWeightKg").value(30))
                .andExpect(jsonPath("$.effectiveAverageSpeedKmh").value(25))
                .andExpect(jsonPath("$.departureLocation.destinationQuery").value("Alasora, Madagascar"))
                .andReturn();

        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(put("/api/admin/couriers/{id}", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Tiana",
                                  "contact": "034 00 000 00",
                                  "active": true,
                                  "vehicleType": "BICYCLE",
                                  "maxWeightKg": 14,
                                  "averageSpeedKmh": 15,
                                  "defaultDepartureTime": "06:45:00",
                                  "departureLocation": {
                                    "label": "Alasora",
                                    "destinationQuery": "Alasora, Madagascar",
                                    "latitude": -18.953,
                                    "longitude": 47.568
                                  },
                                  "version": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleType").value("BICYCLE"))
                .andExpect(jsonPath("$.maxWeightKg").value(14))
                .andExpect(jsonPath("$.effectiveMaxWeightKg").value(14))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(patch("/api/admin/couriers/{id}/disable", id).with(admin())
                        .queryParam("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void rejectsACourierWithoutConfirmedDepartureCoordinates() throws Exception {
        mockMvc.perform(post("/api/admin/couriers").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Tiana",
                                  "vehicleType": "MOTO",
                                  "defaultDepartureTime": "06:30:00",
                                  "departureLocation": { "destinationQuery": "Alasora" }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'departureLocation.latitude')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'departureLocation.longitude')]").exists());
    }

    @Test
    void deniesAdministrationEndpointsToAClient() throws Exception {
        mockMvc.perform(get("/api/admin/settings").with(user("client").roles("CLIENT")))
                .andExpect(status().isForbidden());
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }
}
