package com.azylivraison.importexport.api;

final class CsvTemplates {

    static final String PACKAGES = """
            id;label;weight_kg;destination_query;latitude;longitude;phone
            colis-001;Client Analakely;4.5;Analakely, Antananarivo;;;+261000000000
            colis-002;Client coordonnées;2.0;;-18.9100;47.5250;
            """;

    static final String COURIERS = """
            id;name;vehicle_type;max_weight_kg;average_speed_kmh
            livreur-001;Exemple Livreur;MOTO;35;22
            livreur-002;Exemple Vélo;BICYCLE;;
            """;

    private CsvTemplates() {
    }
}
