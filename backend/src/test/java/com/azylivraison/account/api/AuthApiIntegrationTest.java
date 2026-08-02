package com.azylivraison.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

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
class AuthApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void logsInAndUsesTheAccessToken() throws Exception {
        MvcResult login = login();
        String accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        assertThat(accessToken).isNotBlank();
        assertThat(login.getResponse().getCookie(RefreshCookieService.COOKIE_NAME)).isNotNull();

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void rejectsInvalidCredentialsWithoutDisclosingTheReason() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"unknown","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void protectsBusinessEndpoints() throws Exception {
        mockMvc.perform(get("/api/vehicle-presets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rotatesRefreshTokensAndRejectsTheirReuse() throws Exception {
        MvcResult login = login();
        Cookie originalRefreshToken = login.getResponse().getCookie(RefreshCookieService.COOKIE_NAME);
        assertThat(originalRefreshToken).isNotNull();

        MvcResult refresh = mockMvc.perform(post("/api/auth/refresh").cookie(originalRefreshToken))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(RefreshCookieService.COOKIE_NAME, true))
                .andReturn();

        Cookie replacement = refresh.getResponse().getCookie(RefreshCookieService.COOKIE_NAME);
        assertThat(replacement).isNotNull();
        assertThat(replacement.getValue()).isNotEqualTo(originalRefreshToken.getValue());

        mockMvc.perform(post("/api/auth/refresh").cookie(originalRefreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void revokesTheRefreshTokenOnLogout() throws Exception {
        Cookie refreshToken = login().getResponse().getCookie(RefreshCookieService.COOKIE_NAME);
        assertThat(refreshToken).isNotNull();

        mockMvc.perform(post("/api/auth/logout").cookie(refreshToken))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")));

        mockMvc.perform(post("/api/auth/refresh").cookie(refreshToken))
                .andExpect(status().isUnauthorized());
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "test-admin-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.account.username").value("admin"))
                .andExpect(cookie().httpOnly(RefreshCookieService.COOKIE_NAME, true))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("SameSite=Strict")))
                .andReturn();
    }
}
