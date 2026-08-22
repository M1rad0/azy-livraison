package com.azylivraison.account.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientProfileApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void readsAndUpdatesTheAuthenticatedClientProfile() throws Exception {
        String accessToken = registerClient("profile-" + UUID.randomUUID());

        mockMvc.perform(get("/api/client/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Client Test"))
                .andExpect(jsonPath("$.defaultPickup").doesNotExist())
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(put("/api/client/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Client Modifié",
                                  "phone": "+261 33 11 222 33",
                                  "defaultPickup": {
                                    "label": "Boutique Analakely",
                                    "destinationQuery": "Analakely, Antananarivo",
                                    "latitude": -18.9089,
                                    "longitude": 47.5212
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Client Modifié"))
                .andExpect(jsonPath("$.phone").value("+261 33 11 222 33"))
                .andExpect(jsonPath("$.defaultPickup.label").value("Boutique Analakely"))
                .andExpect(jsonPath("$.defaultPickup.destinationQuery").value("Analakely, Antananarivo"))
                .andExpect(jsonPath("$.defaultPickup.latitude").value(-18.9089))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void validatesDefaultPickupCoordinates() throws Exception {
        String accessToken = registerClient("invalid-pickup-" + UUID.randomUUID());

        mockMvc.perform(put("/api/client/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Client Test",
                                  "phone": "+261340000000",
                                  "defaultPickup": {"latitude": -18.9}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CLIENT_PROFILE"));
    }

    @Test
    void enforcesClientAndAdminBoundaries() throws Exception {
        String clientAccessToken = registerClient("security-" + UUID.randomUUID());
        String adminAccessToken = loginAdmin();

        mockMvc.perform(post("/api/plans/validate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(clientAccessToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/client/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminAccessToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private String registerClient(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "a-secure-password",
                                  "displayName": "Client Test",
                                  "phone": "+261340000000"
                                }
                                """.formatted(username)))
                .andExpect(status().isCreated())
                .andReturn();
        return accessToken(result);
    }

    private String loginAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"test-admin-password"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return accessToken(result);
    }

    private String accessToken(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken")
                .asText();
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
