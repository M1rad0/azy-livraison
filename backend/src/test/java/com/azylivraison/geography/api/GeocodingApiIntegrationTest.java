package com.azylivraison.geography.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.azylivraison.planning.domain.RoutingPreset;
import com.azylivraison.routing.service.RoutingMatrixResult;
import com.azylivraison.routing.service.RoutingService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GeocodingApiIntegrationTest {

    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static final AtomicReference<String> LAST_RAW_QUERY = new AtomicReference<>();
    private static final HttpServer NOMINATIM = startNominatim();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private RoutingService routingService;

    @DynamicPropertySource
    static void geocodingProperties(DynamicPropertyRegistry registry) {
        registry.add("app.geocoding.base-url",
                () -> "http://127.0.0.1:" + NOMINATIM.getAddress().getPort());
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM geocoding_cache");
        jdbcTemplate.update("UPDATE delivery_pricing_policy SET distance_mode = 'AIR_DISTANCE' WHERE id IN (1, 2)");
        REQUEST_COUNT.set(0);
        LAST_RAW_QUERY.set(null);
    }

    @AfterAll
    static void stopNominatim() {
        NOMINATIM.stop(0);
    }

    @Test
    void searchesMadagascarAndMapsNominatimCandidates() throws Exception {
        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "Analakely, Antananarivo")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("Analakely, Antananarivo"))
                .andExpect(jsonPath("$.provider").value("NOMINATIM"))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.resultCount").value(1))
                .andExpect(jsonPath("$.candidates[0].id").value("N12345"))
                .andExpect(jsonPath("$.candidates[0].osmType").value("NODE"))
                .andExpect(jsonPath("$.candidates[0].latitude").value(-18.9089))
                .andExpect(jsonPath("$.candidates[0].longitude").value(47.5212))
                .andExpect(jsonPath("$.candidates[0].boundingBox.south").value(-18.91));

        assertThat(LAST_RAW_QUERY.get())
                .contains("format=jsonv2")
                .contains("countrycodes=mg")
                .contains("accept-language=fr")
                .contains("limit=5");
    }

    @Test
    void boundsDestinationSearchesToTheEffectivePricingCoverage() throws Exception {
        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "Analakely")
                        .queryParam("purpose", "DESTINATION")
                        .queryParam("requestKind", "NORMAL")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(1));

        assertThat(LAST_RAW_QUERY.get())
                .contains("viewbox=")
                .containsOnlyOnce("viewbox=")
                .contains("bounded=1")
                .contains("limit=20")
                .doesNotContain("addressdetails");
    }

    @Test
    void letsAnAdministratorUseTheTargetClientsEffectiveCoverage() throws Exception {
        UUID clientId = UUID.fromString("82000000-0000-0000-0000-000000000099");

        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "Analakely")
                        .queryParam("purpose", "DESTINATION")
                        .queryParam("requestKind", "EXPRESS")
                        .queryParam("clientAccountId", clientId.toString())
                        .with(jwt().jwt(token -> token
                                .claim("role", "ADMIN")
                                .claim("accountId", "81000000-0000-0000-0000-000000000001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(1));
    }

    @Test
    void removesProviderCandidatesOutsideTheMaximumPricingRadius() throws Exception {
        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "Toliara")
                        .queryParam("purpose", "DESTINATION")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(0));
    }

    @Test
    void doesNotReuseAnOperationalCacheEntryForADestinationSearch() throws Exception {
        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "Analakely")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "Analakely")
                        .queryParam("purpose", "DESTINATION")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false));

        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "Analakely")
                        .queryParam("purpose", "DESTINATION")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true));

        assertThat(REQUEST_COUNT).hasValue(2);
    }

    @Test
    void checksRoadCoverageWithOneRoutingMatrixCall() throws Exception {
        jdbcTemplate.update("UPDATE delivery_pricing_policy SET distance_mode = 'ROAD_DISTANCE' WHERE id = 1");
        when(routingService.matrix(anyList())).thenReturn(new RoutingMatrixResult(
                "OSRM", RoutingPreset.MAIN_ROADS, java.util.List.of(),
                java.util.List.of(java.util.List.of(0d, 20_000d), java.util.List.of(20_000d, 0d)),
                java.util.List.of(java.util.List.of(0d, 20_000d), java.util.List.of(20_000d, 0d)),
                java.util.List.of()));

        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "Analakely")
                        .queryParam("purpose", "DESTINATION")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(0));
    }

    @Test
    void reusesCacheForEquivalentQueries() throws Exception {
        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "Analakely Antananarivo")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false));

        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "  ANALAKELY   ANTANANARIVO ")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.cachedAt").exists())
                .andExpect(jsonPath("$.candidates[0].displayName")
                        .value("Analakely, Antananarivo, Madagascar"));

        assertThat(REQUEST_COUNT).hasValue(1);
    }

    @Test
    void rejectsAnInvalidQuery() throws Exception {
        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "a")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GEOCODING_QUERY"));
    }

    @Test
    void mapsProviderFailuresToBadGateway() throws Exception {
        mockMvc.perform(get("/api/geocoding/search")
                        .queryParam("q", "service unavailable")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GEOCODING_UNAVAILABLE"));
    }

    @Test
    void exposesTheSearchEndpointForThePublicPricingCalculator() throws Exception {
        mockMvc.perform(get("/api/geocoding/search").queryParam("q", "Analakely"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].displayName").exists());
    }

    private static HttpServer startNominatim() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/search", GeocodingApiIntegrationTest::respond);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        REQUEST_COUNT.incrementAndGet();
        LAST_RAW_QUERY.set(exchange.getRequestURI().getRawQuery());
        if (exchange.getRequestURI().getRawQuery().contains("service%20unavailable")
                || exchange.getRequestURI().getRawQuery().contains("service+unavailable")) {
            send(exchange, 503, "{\"error\":\"unavailable\"}");
            return;
        }
        boolean farAway = exchange.getRequestURI().getRawQuery().contains("Toliara");
        String latitude = farAway ? "-23.3500" : "-18.9089";
        String longitude = farAway ? "43.6667" : "47.5212";
        send(exchange, 200, """
                [{
                  "place_id": 42,
                  "osm_type": "node",
                  "osm_id": 12345,
                  "lat": "%s",
                  "lon": "%s",
                  "display_name": "Analakely, Antananarivo, Madagascar",
                  "category": "place",
                  "type": "suburb",
                  "importance": 0.62,
                  "boundingbox": ["-18.91", "-18.90", "47.51", "47.53"]
                }]
                """.formatted(latitude, longitude));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
