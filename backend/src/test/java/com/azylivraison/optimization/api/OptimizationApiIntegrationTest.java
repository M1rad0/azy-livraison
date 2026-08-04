package com.azylivraison.optimization.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
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
class OptimizationApiIntegrationTest {

    private static final HttpServer OSRM = startOsrm();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void optimizationProperties(DynamicPropertyRegistry registry) {
        registry.add("app.routing.base-url", () -> "http://127.0.0.1:" + OSRM.getAddress().getPort());
        registry.add("app.optimization.time-limit", () -> "2s");
    }

    @AfterAll
    static void stopOsrm() {
        OSRM.stop(0);
    }

    @Test
    void optimizesAPlanAndExplainsEveryUnassignedPackage() throws Exception {
        mockMvc.perform(post("/api/plans/optimize")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planWithOneDeliverableAndOneOverweightPackage()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.sourcePlanId").value("plan-opt-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS_WITH_WARNINGS"))
                .andExpect(jsonPath("$.dataContext.routingProvider").value("OSRM"))
                .andExpect(jsonPath("$.dataContext.optimizer").value("OR_TOOLS"))
                .andExpect(jsonPath("$.summary.packageCount").value(2))
                .andExpect(jsonPath("$.summary.assignedPackageCount").value(1))
                .andExpect(jsonPath("$.summary.unassignedPackageCount").value(1))
                .andExpect(jsonPath("$.routes.length()").value(1))
                .andExpect(jsonPath("$.routes[0].courierId").value("livreur-001"))
                .andExpect(jsonPath("$.routes[0].loadedWeightKg").value(2))
                .andExpect(jsonPath("$.routes[0].stops[0].packageId").value("colis-001"))
                .andExpect(jsonPath("$.routes[0].stops[0].order").value(1))
                .andExpect(jsonPath("$.unassignedPackages[0].packageId").value("colis-lourd"))
                .andExpect(jsonPath("$.unassignedPackages[0].reason").value("OVERWEIGHT_FOR_ALL_COURIERS"));
    }

    @Test
    void rejectsAnUnresolvedDepotWithAnExplicitBusinessError() throws Exception {
        String unresolvedDepot = planWithOneDeliverableAndOneOverweightPackage()
                .replace("\"latitude\": -18.9089, \"longitude\": 47.5212", "\"destinationQuery\": \"Analakely\"");

        mockMvc.perform(post("/api/plans/optimize")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unresolvedDepot))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPTIMIZATION_INPUT_INVALID"));
    }

    @Test
    void protectsTheOptimizationEndpoint() throws Exception {
        mockMvc.perform(post("/api/plans/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planWithOneDeliverableAndOneOverweightPackage()))
                .andExpect(status().isUnauthorized());
    }

    private static String planWithOneDeliverableAndOneOverweightPackage() {
        return """
                {
                  "schemaVersion": "1.0",
                  "pattern": "SINGLE_PICKUP_MULTI_DELIVERY",
                  "planId": "plan-opt-001",
                  "name": "Tournée test",
                  "departureAt": "2026-08-05T08:00:00+03:00",
                  "timezone": "Indian/Antananarivo",
                  "settings": { "returnToDepot": false, "routingPreset": "MAIN_ROADS" },
                  "depot": {
                    "label": "Analakely",
                    "latitude": -18.9089, "longitude": 47.5212
                  },
                  "couriers": [{
                    "id": "livreur-001",
                    "name": "Soa",
                    "vehicleType": "MOTO",
                    "maxWeightKg": 5,
                    "averageSpeedKmh": 30
                  }],
                  "packages": [
                    {
                      "id": "colis-001",
                      "label": "Client Ankorondrano",
                      "weightKg": 2,
                      "destination": {
                        "label": "Ankorondrano",
                        "latitude": -18.8792, "longitude": 47.5175
                      }
                    },
                    {
                      "id": "colis-lourd",
                      "label": "Colis impossible",
                      "weightKg": 10,
                      "destination": {
                        "label": "Ivandry",
                        "latitude": -18.865, "longitude": 47.52
                      }
                    }
                  ]
                }
                """;
    }

    private static HttpServer startOsrm() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/table/v1/driving/", OptimizationApiIntegrationTest::respondTable);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void respondTable(HttpExchange exchange) throws IOException {
        byte[] bytes = """
                {
                  "code":"Ok",
                  "durations":[[0,420],[430,0]],
                  "distances":[[0,2900],[2950,0]],
                  "sources":[
                    {"location":[47.5212,-18.9089],"distance":0,"name":"Analakely"},
                    {"location":[47.5175,-18.8792],"distance":0,"name":"Ankorondrano"}
                  ],
                  "destinations":[]
                }
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
