package com.azylivraison.geography.api;

import com.azylivraison.geography.service.GeocodingService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geocoding")
public class GeocodingController {

    private final GeocodingService geocodingService;

    public GeocodingController(GeocodingService geocodingService) {
        this.geocodingService = geocodingService;
    }

    @GetMapping("/search")
    public GeocodingSearchResponse search(@RequestParam(name = "q", required = false) String query) {
        return GeocodingSearchResponse.from(geocodingService.search(query));
    }
}
