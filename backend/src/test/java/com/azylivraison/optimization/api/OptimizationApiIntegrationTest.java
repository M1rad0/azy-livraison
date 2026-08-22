package com.azylivraison.optimization.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

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
    void includesTheTripFromTheCourierStartToThePickupPoint() throws Exception {
        mockMvc.perform(post("/api/plans/optimize")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planWithCourierDeparture()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes.length()").value(1))
                .andExpect(jsonPath("$.routes[0].departureLocation.label").value("Départ livreur"))
                .andExpect(jsonPath("$.routes[0].distanceMeters").value(4100))
                .andExpect(jsonPath("$.routes[0].stops[0].distanceFromPreviousMeters").value(2900))
                .andExpect(jsonPath("$.routes[0].stops[0].cumulativeDistanceMeters").value(4100))
                .andExpect(jsonPath("$.routes[0].stops[0].estimatedArrivalAt").value(startsWith("2026-08-05T08:18:")));
    }

    @Test
    void protectsTheOptimizationEndpoint() throws Exception {
        mockMvc.perform(post("/api/plans/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planWithOneDeliverableAndOneOverweightPackage()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void optimizesMultipleClientsWithPickupBeforeEveryDelivery() throws Exception {
        mockMvc.perform(post("/api/plans/optimize")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multiClientPlan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("2.0"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.summary.packageCount").value(2))
                .andExpect(jsonPath("$.summary.assignedPackageCount").value(2))
                .andExpect(jsonPath("$.routes.length()").value(1))
                .andExpect(jsonPath("$.routes[0].loadedWeightKg").value(2))
                .andExpect(jsonPath("$.routes[0].stops.length()").value(4))
                .andExpect(jsonPath("$.routes[0].stops[?(@.type == 'PICKUP')]").value(hasSize(2)))
                .andExpect(jsonPath("$.routes[0].stops[?(@.type == 'DELIVERY')]").value(hasSize(2)))
                .andExpect(jsonPath("$.routes[0].stops[*].clientName").value(hasItem("Client Alpha")));
    }

    @Test
    void startsAMultiClientRouteAtTheCourierOriginBeforeTheFirstPickup() throws Exception {
        mockMvc.perform(post("/api/plans/optimize")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multiClientPlanWithCourierDeparture()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.summary.assignedPackageCount").value(1))
                .andExpect(jsonPath("$.routes.length()").value(1))
                .andExpect(jsonPath("$.routes[0].departureLocation.label").value("Alasora"))
                .andExpect(jsonPath("$.routes[0].departureAt").value(startsWith("2026-08-05T06:00:")))
                .andExpect(jsonPath("$.routes[0].distanceMeters").value(1700))
                .andExpect(jsonPath("$.routes[0].stops.length()").value(2))
                .andExpect(jsonPath("$.routes[0].stops[0].type").value("PICKUP"))
                .andExpect(jsonPath("$.routes[0].stops[0].deliveryGroupName").value("Retrait Ampasampito"))
                .andExpect(jsonPath("$.routes[0].stops[0].distanceFromPreviousMeters").value(1200))
                .andExpect(jsonPath("$.routes[0].stops[0].estimatedArrivalAt")
                        .value(startsWith("2026-08-05T06:02:")))
                .andExpect(jsonPath("$.routes[0].stops[1].type").value("DELIVERY"));
    }

    @Test
    void comparesDirectAndHubStrategiesWithACompleteTransferManifest() throws Exception {
        mockMvc.perform(post("/api/plans/compare")
                        .with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multiClientPlanWithHub()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.recommendedStrategy").value(notNullValue()))
                .andExpect(jsonPath("$.operationalPolicy.pickupServiceSeconds").value(600))
                .andExpect(jsonPath("$.operationalPolicy.hubTransferSeconds").value(900))
                .andExpect(jsonPath("$.direct.strategy").value("DIRECT"))
                .andExpect(jsonPath("$.direct.metrics.assignedPackageCount").value(1))
                .andExpect(jsonPath("$.viaHub.strategy").value("VIA_HUB"))
                .andExpect(jsonPath("$.viaHub.metrics.assignedPackageCount").value(1))
                .andExpect(jsonPath("$.viaHub.result.routes[0].stops[?(@.type == 'PICKUP')]").value(hasSize(1)))
                .andExpect(jsonPath("$.viaHub.result.routes[0].stops[?(@.type == 'DELIVERY')]").value(hasSize(1)))
                .andExpect(jsonPath("$.hubExecution.hub.label").value("Point de rencontre"))
                .andExpect(jsonPath("$.hubExecution.meetingAt").value(notNullValue()))
                .andExpect(jsonPath("$.hubExecution.packageTransfers[0].collectorCourierId").value("livreur-001"))
                .andExpect(jsonPath("$.hubExecution.packageTransfers[0].distributorCourierId").value("livreur-001"));
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

    private static String planWithCourierDeparture() {
        return planWithOneDeliverableAndOneOverweightPackage().replace(
                "\"averageSpeedKmh\": 30",
                """
                "averageSpeedKmh": 30,
                    "departureAt": "2026-08-05T08:00:00+03:00",
                    "departureLocation": {
                      "label": "Départ livreur",
                      "latitude": -18.9200, "longitude": 47.5000
                    }
                """.strip());
    }

    private static String multiClientPlan() {
        return """
                {
                  "schemaVersion": "2.0",
                  "pattern": "MULTI_CLIENT_MULTI_PICKUP_DELIVERY",
                  "planId": "multi-opt-001",
                  "name": "Deux clients",
                  "departureAt": "2026-08-05T08:00:00+03:00",
                  "timezone": "Indian/Antananarivo",
                  "settings": { "returnToDepot": false, "routingPreset": "MAIN_ROADS" },
                  "couriers": [{
                    "id": "livreur-001", "name": "Soa", "vehicleType": "MOTO",
                    "maxWeightKg": 2, "averageSpeedKmh": 30
                  }],
                  "clients": [
                    {
                      "id": "client-alpha", "name": "Client Alpha",
                      "deliveryGroups": [{
                        "id": "groupe-alpha", "name": "Course Alpha",
                        "pickup": { "label": "Retrait Alpha", "latitude": -18.9089, "longitude": 47.5212 },
                        "packages": [{
                          "id": "colis-alpha", "label": "Destinataire Alpha", "weightKg": 2,
                          "destination": { "label": "Ankorondrano", "latitude": -18.8792, "longitude": 47.5175 }
                        }]
                      }]
                    },
                    {
                      "id": "client-beta", "name": "Client Beta",
                      "deliveryGroups": [{
                        "id": "groupe-beta", "name": "Course Beta",
                        "pickup": { "label": "Retrait Beta", "latitude": -18.8650, "longitude": 47.5200 },
                        "packages": [{
                          "id": "colis-beta", "label": "Destinataire Beta", "weightKg": 2,
                          "destination": { "label": "Ambodivona", "latitude": -18.8900, "longitude": 47.5300 }
                        }]
                      }]
                    }
                  ]
                }
                """;
    }

    private static String multiClientPlanWithCourierDeparture() {
        return """
                {
                  "schemaVersion": "2.0",
                  "pattern": "MULTI_CLIENT_MULTI_PICKUP_DELIVERY",
                  "planId": "multi-custom-start-001",
                  "name": "Départ Alasora",
                  "departureAt": "2026-08-05T06:00:00+03:00",
                  "timezone": "Indian/Antananarivo",
                  "settings": { "returnToDepot": false, "routingPreset": "MAIN_ROADS" },
                  "couriers": [{
                    "id": "livreur-001", "name": "Omni Man", "vehicleType": "MOTO",
                    "maxWeightKg": 30, "averageSpeedKmh": 30,
                    "departureAt": "2026-08-05T06:00:00+03:00",
                    "departureLocation": {
                      "label": "Alasora", "latitude": -18.9553235, "longitude": 47.5674821
                    }
                  }],
                  "clients": [{
                    "id": "client-001", "name": "Client",
                    "deliveryGroups": [{
                      "id": "course-001", "name": "Retrait Ampasampito",
                      "pickup": {
                        "label": "Ampasampito", "latitude": -18.8957014, "longitude": 47.545794
                      },
                      "packages": [{
                        "id": "colis-001", "label": "Colis", "weightKg": 1.5,
                        "destination": {
                          "label": "Destination", "latitude": -18.9385813, "longitude": 47.5281625
                        }
                      }]
                    }]
                  }]
                }
                """;
    }

    private static String multiClientPlanWithHub() {
        return multiClientPlanWithCourierDeparture()
                .replace("\"schemaVersion\": \"2.0\"", "\"schemaVersion\": \"2.1\"")
                .replace(
                        "\"clients\": [{",
                        """
                        "redistributionHub": {
                          "label": "Point de rencontre",
                          "latitude": -18.9100,
                          "longitude": 47.5300
                        },
                        "clients": [{
                        """.strip());
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
        boolean includesCourierStart = exchange.getRequestURI().getPath().chars()
                .filter(character -> character == ';')
                .count() == 2;
        long separatorCount = exchange.getRequestURI().getPath().chars()
                .filter(character -> character == ';')
                .count();
        String response = separatorCount == 3 ? """
                {
                  "code":"Ok",
                  "durations":[[0,360,120,300],[360,0,300,120],[120,300,0,240],[300,120,240,0]],
                  "distances":[[0,3000,1000,2500],[3000,0,2500,1000],[1000,2500,0,2000],[2500,1000,2000,0]],
                  "sources":[
                    {"location":[47.5212,-18.9089],"distance":0,"name":"Retrait Alpha"},
                    {"location":[47.5200,-18.8650],"distance":0,"name":"Retrait Beta"},
                    {"location":[47.5175,-18.8792],"distance":0,"name":"Ankorondrano"},
                    {"location":[47.5300,-18.8900],"distance":0,"name":"Ambodivona"}
                  ],
                  "destinations":[]
                }
                """ : includesCourierStart ? """
                {
                  "code":"Ok",
                  "durations":[[0,144,348],[144,0,60],[354,72,0]],
                  "distances":[[0,1200,2900],[1200,0,500],[2950,600,0]],
                  "sources":[
                    {"location":[47.5212,-18.9089],"distance":0,"name":"Analakely"},
                    {"location":[47.5000,-18.9200],"distance":0,"name":"Départ livreur"},
                    {"location":[47.5175,-18.8792],"distance":0,"name":"Ankorondrano"}
                  ],
                  "destinations":[]
                }
                """ : """
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
                """;
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
