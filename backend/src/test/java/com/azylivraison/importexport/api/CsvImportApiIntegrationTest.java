package com.azylivraison.importexport.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties = "app.imports.max-file-size=1KB")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsvImportApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void importsPackagesWithBomTextDestinationsCoordinatesAndDecimalComma() throws Exception {
        MockMultipartFile file = csv("packages.csv", "\uFEFF" + """
                id;label;weight_kg;destination_query;latitude;longitude;phone;window_start_at;window_end_at
                colis-001;Client Analakely;4,5;Analakely, Antananarivo;;;+261000000000;2026-08-05T09:00:00+03:00;2026-08-05T11:00:00+03:00
                colis-002;Client coordonnées;2.0;;-18.9100;47.5250;;;
                """);

        mockMvc.perform(multipart("/api/import/packages/csv")
                        .file(file)
                        .with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.importType").value("PACKAGES"))
                .andExpect(jsonPath("$.delimiter").value(";"))
                .andExpect(jsonPath("$.importedCount").value(2))
                .andExpect(jsonPath("$.items[0].weightKg").value(4.5))
                .andExpect(jsonPath("$.items[0].destination.destinationQuery")
                        .value("Analakely, Antananarivo"))
                .andExpect(jsonPath("$.items[0].deliveryWindow.startAt")
                        .value("2026-08-05T09:00:00+03:00"))
                .andExpect(jsonPath("$.items[1].destination.latitude").value(-18.91))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void importsCommaSeparatedCouriersAndAppliesVehicleDefaults() throws Exception {
        MockMultipartFile file = csv("couriers.csv", """
                id,name,vehicle_type,max_weight_kg,average_speed_kmh,departure_at,departure_query,departure_latitude,departure_longitude
                livreur-001,Exemple Moto,MOTO,35,22,2026-08-05T08:00:00+03:00,"Analakely, Antananarivo",,
                livreur-002,Exemple Vélo,bicycle,,,,,,
                """);

        mockMvc.perform(multipart("/api/import/couriers/csv")
                        .file(file)
                        .with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.importType").value("COURIERS"))
                .andExpect(jsonPath("$.delimiter").value(","))
                .andExpect(jsonPath("$.items[0].maxWeightKg").value(35))
                .andExpect(jsonPath("$.items[0].departureLocation.destinationQuery")
                        .value("Analakely, Antananarivo"))
                .andExpect(jsonPath("$.items[1].vehicleType").value("BICYCLE"))
                .andExpect(jsonPath("$.items[1].maxWeightKg").value(10))
                .andExpect(jsonPath("$.items[1].averageSpeedKmh").value(12));
    }

    @Test
    void reportsAllRowErrorsWithoutReturningPartialItems() throws Exception {
        MockMultipartFile file = csv("packages.csv", """
                id;label;weight_kg;destination_query;latitude;longitude;phone;window_start_at;window_end_at
                colis-001;Premier;1;Analakely;;;;;
                colis-001;;0;;;47.5;;;
                """);

        mockMvc.perform(multipart("/api/import/packages/csv")
                        .file(file)
                        .with(user("admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.importedCount").value(0))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.errors[?(@.line == 3 && @.code == 'DUPLICATE_ID')]").exists())
                .andExpect(jsonPath("$.errors[?(@.line == 3 && @.field == 'label')]").exists())
                .andExpect(jsonPath("$.errors[?(@.line == 3 && @.code == 'NOT_POSITIVE')]").exists())
                .andExpect(jsonPath("$.errors[?(@.line == 3 && @.code == 'INCOMPLETE_COORDINATES')]").exists());
    }

    @Test
    void rejectsMissingAndUnknownHeaders() throws Exception {
        MockMultipartFile file = csv("packages.csv", """
                id;label;unexpected
                colis-001;Test;value
                """);

        mockMvc.perform(multipart("/api/import/packages/csv")
                        .file(file)
                        .with(user("admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.code == 'MISSING_HEADER')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'unexpected' && @.code == 'UNKNOWN_HEADER')]")
                        .exists());
    }

    @Test
    void rejectsAnIncompatibleContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "packages.csv",
                "image/png",
                "not-an-image-or-csv".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/import/packages/csv")
                        .file(file)
                        .with(user("admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_CONTENT_TYPE"));
    }

    @Test
    void rejectsAnOversizedFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "packages.csv",
                "text/csv",
                new byte[1_025]);

        mockMvc.perform(multipart("/api/import/packages/csv")
                        .file(file)
                        .with(user("admin")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errors[0].code").value("FILE_TOO_LARGE"));
    }

    @Test
    void rejectsNonUtf8Input() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "packages.csv",
                "text/csv",
                new byte[]{(byte) 0xC3, (byte) 0x28});

        mockMvc.perform(multipart("/api/import/packages/csv")
                        .file(file)
                        .with(user("admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_ENCODING"));
    }

    @Test
    void rejectsAMissingMultipartFile() throws Exception {
        mockMvc.perform(multipart("/api/import/packages/csv").with(user("admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("MISSING_FILE"));
    }

    @Test
    void protectsImportEndpoints() throws Exception {
        mockMvc.perform(multipart("/api/import/packages/csv")
                        .file(csv("packages.csv", "id;label\n")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadsThePackageTemplate() throws Exception {
        mockMvc.perform(get("/api/import/packages/template").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("packages-template.csv")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "id;label;weight_kg;destination_query;latitude;longitude;phone;window_start_at;window_end_at")));
    }

    private MockMultipartFile csv(String filename, String content) {
        return new MockMultipartFile(
                "file",
                filename,
                "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
