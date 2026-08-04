package com.azylivraison.importexport.api;

final class CsvTemplates {

    static final String PACKAGES = """
            id;label;weight_kg;destination_query;latitude;longitude;phone;window_start_at;window_end_at
            colis-001;Client Analakely;4.5;Analakely, Antananarivo;;;+261000000000;2026-08-05T09:00:00+03:00;2026-08-05T11:00:00+03:00
            colis-002;Client coordonnées;2.0;;-18.9100;47.5250;;;
            """;

    static final String COURIERS = """
            id;name;vehicle_type;max_weight_kg;average_speed_kmh;departure_at;departure_query;departure_latitude;departure_longitude
            livreur-001;Exemple Livreur;MOTO;35;22;2026-08-05T08:00:00+03:00;Analakely, Antananarivo;;
            livreur-002;Exemple Vélo;BICYCLE;;;;;;
            """;

    private CsvTemplates() {
    }
}
