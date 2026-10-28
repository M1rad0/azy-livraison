package com.azylivraison.geography.provider;

/** Optional spatial restriction passed to the geocoding provider. */
public record GeocodingSearchOptions(
        Double centerLatitude,
        Double centerLongitude,
        Long maximumDistanceMeters) {

    public static GeocodingSearchOptions unrestricted() {
        return new GeocodingSearchOptions(null, null, null);
    }

    public static GeocodingSearchOptions bounded(
            double centerLatitude,
            double centerLongitude,
            long maximumDistanceMeters) {
        return new GeocodingSearchOptions(centerLatitude, centerLongitude, maximumDistanceMeters);
    }

    public boolean bounded() {
        return centerLatitude != null && centerLongitude != null && maximumDistanceMeters != null;
    }
}
