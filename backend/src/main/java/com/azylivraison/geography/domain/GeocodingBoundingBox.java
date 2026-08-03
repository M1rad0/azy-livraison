package com.azylivraison.geography.domain;

public record GeocodingBoundingBox(
        double south,
        double north,
        double west,
        double east) {
}
