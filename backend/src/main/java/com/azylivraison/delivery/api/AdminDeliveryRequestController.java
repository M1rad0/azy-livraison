package com.azylivraison.delivery.api;

import java.time.LocalDate;
import java.util.List;

import com.azylivraison.delivery.domain.DeliveryRequestStatus;
import com.azylivraison.delivery.service.DeliveryRequestService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminDeliveryRequestController {

    private final DeliveryRequestService service;

    public AdminDeliveryRequestController(DeliveryRequestService service) {
        this.service = service;
    }

    @GetMapping("/delivery-requests")
    public List<DeliveryRequestResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) DeliveryRequestStatus status) {
        return service.findForAdministration(date, status);
    }

    @GetMapping("/queue")
    public AdminQueueResponse queue(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.queue(date);
    }
}
