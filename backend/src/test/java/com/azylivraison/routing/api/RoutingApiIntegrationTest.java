package com.azylivraison.routing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoutingApiIntegrationTest {

    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();
    private static final HttpServer OSRM = startOsrm();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void routingProperties(DynamicPropertyRegistry registry) {
        registry.add("app.routing.base-url", () -> "http://127.0.0.1:" + OSRM.getAddress().getPort());
        registry.add("app.routing.max-matrix-points", () -> 3);
    }

    @BeforeEach
    void resetRequest() {
        LAST_REQUEST.set(null);
    }

    @AfterAll
    static void stopOsrm() {
        OSRM.stop(0);
    }

    @Test
    void returnsDistanceAndDurationMatricesWithSnappedPoints() throws Exception {
        mockMvc.perform(post("/api/routing/matrix")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoPoints(47.522924)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("OSRM"))
                .andExpect(jsonPath("$.preset").value("MAIN_ROADS"))
                .andExpect(jsonPath("$.pointCount").value(2))
                .andExpect(jsonPath("$.durationsSeconds[0][1]").value(302.4))
                .andExpect(jsonPath("$.distancesMeters[1][0]").value(4210.8))
                .andExpect(jsonPath("$.waypoints[0].id").value("pickup"))
                .andExpect(jsonPath("$.waypoints[0].snappedLatitude").value(-18.9088))
                .andExpect(jsonPath("$.waypoints[0].distanceToRoadMeters").value(12.3))
                .andExpect(jsonPath("$.unreachablePairs").isEmpty());

        assertThat(LAST_REQUEST.get())
                .contains("/table/v1/driving/47.5212000,-18.9089000;47.5229240,-18.8859714")
                .contains("annotations=duration,distance");
    }

    @Test
    void reportsEveryUnreachableMatrixPair() throws Exception {
        mockMvc.perform(post("/api/routing/matrix")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoPoints(48.0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreachablePairs.length()").value(2))
                .andExpect(jsonPath("$.unreachablePairs[0].fromId").value("pickup"))
                .andExpect(jsonPath("$.unreachablePairs[0].toId").value("package-1"))
                .andExpect(jsonPath("$.unreachablePairs[1].fromId").value("package-1"))
                .andExpect(jsonPath("$.unreachablePairs[1].toId").value("pickup"));
    }

    @Test
    void returnsRouteGeometryLegsAndTotals() throws Exception {
        mockMvc.perform(post("/api/routing/route")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoPoints(47.522924)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceMeters").value(4321.5))
                .andExpect(jsonPath("$.durationSeconds").value(315.7))
                .andExpect(jsonPath("$.geometry.type").value("LineString"))
                .andExpect(jsonPath("$.geometry.coordinates.length()").value(3))
                .andExpect(jsonPath("$.geometry.coordinates[0][0]").value(47.5211))
                .andExpect(jsonPath("$.legs[0].fromId").value("pickup"))
                .andExpect(jsonPath("$.legs[0].toId").value("package-1"))
                .andExpect(jsonPath("$.legs[0].summary").value("Avenue de l'Indépendance"));

        assertThat(LAST_REQUEST.get())
                .contains("/route/v1/driving/")
                .contains("geometries=geojson")
                .contains("overview=full");
    }

    @Test
    void rejectsDuplicatePointIdentifiers() throws Exception {
        String body = twoPoints(47.522924).replace("package-1", "pickup");
        mockMvc.perform(post("/api/routing/matrix")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ROUTING_REQUEST"));
    }

    @Test
    void rejectsMatricesAboveTheConfiguredLimit() throws Exception {
        mockMvc.perform(post("/api/routing/matrix")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"points":[
                                  {"id":"a","latitude":-18.9,"longitude":47.5},
                                  {"id":"b","latitude":-18.8,"longitude":47.6},
                                  {"id":"c","latitude":-18.7,"longitude":47.7},
                                  {"id":"d","latitude":-18.6,"longitude":47.8}
                                ]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ROUTING_REQUEST"));
    }

    @Test
    void mapsMissingRoutesAndProviderFailures() throws Exception {
        mockMvc.perform(post("/api/routing/route")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoPoints(49.8)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));

        mockMvc.perform(post("/api/routing/route")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoPoints(49.9)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ROUTING_UNAVAILABLE"));
    }

    @Test
    void protectsRoutingEndpoints() throws Exception {
        mockMvc.perform(post("/api/routing/matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoPoints(47.522924)))
                .andExpect(status().isUnauthorized());
    }

    private static String twoPoints(double destinationLongitude) {
        return """
                {"points":[
                  {"id":"pickup","latitude":-18.9089,"longitude":47.5212},
                  {"id":"package-1","latitude":-18.8859714,"longitude":%s}
                ]}
                """.formatted(destinationLongitude);
    }

    private static HttpServer startOsrm() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/table/v1/driving/", RoutingApiIntegrationTest::respondTable);
            server.createContext("/route/v1/driving/", RoutingApiIntegrationTest::respondRoute);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void respondTable(HttpExchange exchange) throws IOException {
        remember(exchange);
        if (exchange.getRequestURI().getRawPath().contains("48.0000000")) {
            send(exchange, 200, matrixPayload("null", "null"));
            return;
        }
        send(exchange, 200, matrixPayload("302.4", "318.1"));
    }

    private static void respondRoute(HttpExchange exchange) throws IOException {
        remember(exchange);
        String path = exchange.getRequestURI().getRawPath();
        if (path.contains("49.9000000")) {
            send(exchange, 503, "{\"code\":\"InternalError\",\"message\":\"unavailable\"}");
            return;
        }
        if (path.contains("49.8000000")) {
            send(exchange, 400, "{\"code\":\"NoRoute\",\"message\":\"No route found\"}");
            return;
        }
        send(exchange, 200, """
                {
                  "code":"Ok",
                  "waypoints":[
                    {"location":[47.5211,-18.9088],"distance":12.3,"name":"Analakely"},
                    {"location":[47.5229,-18.8860],"distance":8.4,"name":"Làlana Ravoninahitriniarivo"}
                  ],
                  "routes":[{
                    "distance":4321.5,
                    "duration":315.7,
                    "geometry":{"type":"LineString","coordinates":[[47.5211,-18.9088],[47.524,-18.897],[47.5229,-18.8860]]},
                    "legs":[{"distance":4321.5,"duration":315.7,"summary":"Avenue de l'Indépendance"}]
                  }]
                }
                """);
    }

    private static String matrixPayload(String forwardDuration, String backwardDuration) {
        return """
                {
                  "code":"Ok",
                  "durations":[[0,%s],[%s,0]],
                  "distances":[[0,4198.2],[4210.8,0]],
                  "sources":[
                    {"location":[47.5211,-18.9088],"distance":12.3,"name":"Analakely"},
                    {"location":[47.5229,-18.8860],"distance":8.4,"name":"Làlana Ravoninahitriniarivo"}
                  ],
                  "destinations":[]
                }
                """.formatted(forwardDuration, backwardDuration);
    }

    private static void remember(HttpExchange exchange) {
        LAST_REQUEST.set(exchange.getRequestURI().toString());
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
