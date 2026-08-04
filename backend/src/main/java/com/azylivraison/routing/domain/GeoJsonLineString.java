package com.azylivraison.routing.domain;

import java.util.List;

public record GeoJsonLineString(
        String type,
        List<List<Double>> coordinates) {

    public GeoJsonLineString(List<List<Double>> coordinates) {
        this("LineString", coordinates);
    }
}
