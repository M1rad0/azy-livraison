package com.azylivraison.system;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

    private final DatabaseStatusService databaseStatusService;

    public SystemStatusController(DatabaseStatusService databaseStatusService) {
        this.databaseStatusService = databaseStatusService;
    }

    @GetMapping("/status")
    public ResponseEntity<SystemStatusResponse> status() {
        boolean databaseAvailable = databaseStatusService.isAvailable();
        SystemStatusResponse response = new SystemStatusResponse(
                "UP",
                databaseAvailable ? "UP" : "DOWN",
                Instant.now());

        return ResponseEntity
                .status(databaseAvailable ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
    }

    public record SystemStatusResponse(String service, String database, Instant checkedAt) {
    }
}

