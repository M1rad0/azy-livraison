CREATE TABLE operational_settings (
    id SMALLINT DEFAULT 1 PRIMARY KEY CHECK (id = 1),
    daily_package_limit INTEGER NOT NULL DEFAULT 100 CHECK (daily_package_limit > 0),
    default_hub_label VARCHAR(200),
    default_hub_query VARCHAR(500),
    default_hub_latitude NUMERIC(9, 6),
    default_hub_longitude NUMERIC(9, 6),
    default_package_weight_kg NUMERIC(10, 3) NOT NULL DEFAULT 1 CHECK (default_package_weight_kg > 0),
    pickup_service_minutes INTEGER NOT NULL DEFAULT 10 CHECK (pickup_service_minutes > 0),
    hub_service_minutes INTEGER NOT NULL DEFAULT 15 CHECK (hub_service_minutes > 0),
    delivery_service_minutes INTEGER NOT NULL DEFAULT 5 CHECK (delivery_service_minutes > 0),
    early_tolerance_minutes INTEGER NOT NULL DEFAULT 0 CHECK (early_tolerance_minutes >= 0),
    late_tolerance_minutes INTEGER NOT NULL DEFAULT 30 CHECK (late_tolerance_minutes >= 0),
    optimization_time_limit_seconds INTEGER NOT NULL DEFAULT 5 CHECK (optimization_time_limit_seconds > 0),
    routing_preset VARCHAR(50) NOT NULL DEFAULT 'MAIN_ROADS'
        CHECK (routing_preset IN ('MAIN_ROADS')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT operational_settings_hub_coordinates_check CHECK (
        (default_hub_label IS NULL AND default_hub_query IS NULL
            AND default_hub_latitude IS NULL AND default_hub_longitude IS NULL)
        OR (default_hub_latitude IS NOT NULL AND default_hub_longitude IS NOT NULL
            AND default_hub_latitude BETWEEN -90 AND 90
            AND default_hub_longitude BETWEEN -180 AND 180)
    )
);

INSERT INTO operational_settings (id) VALUES (1);

CREATE TABLE vehicle_preset (
    vehicle_type VARCHAR(30) PRIMARY KEY,
    max_weight_kg NUMERIC(10, 3) NOT NULL CHECK (max_weight_kg > 0),
    average_speed_kmh NUMERIC(10, 3) NOT NULL CHECK (average_speed_kmh > 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT vehicle_preset_type_check CHECK (vehicle_type IN ('MOTO', 'BICYCLE'))
);

INSERT INTO vehicle_preset (vehicle_type, max_weight_kg, average_speed_kmh)
VALUES ('MOTO', 30, 25), ('BICYCLE', 10, 12);

CREATE TABLE courier_profile (
    id UUID PRIMARY KEY,
    account_id UUID UNIQUE REFERENCES app_account(id) ON DELETE SET NULL,
    name VARCHAR(150) NOT NULL,
    contact VARCHAR(100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    vehicle_type VARCHAR(30) NOT NULL REFERENCES vehicle_preset(vehicle_type),
    max_weight_kg_override NUMERIC(10, 3) CHECK (max_weight_kg_override > 0),
    average_speed_kmh_override NUMERIC(10, 3) CHECK (average_speed_kmh_override > 0),
    default_departure_time TIME NOT NULL,
    departure_location_label VARCHAR(200),
    departure_location_query VARCHAR(500),
    departure_latitude NUMERIC(9, 6) NOT NULL CHECK (departure_latitude BETWEEN -90 AND 90),
    departure_longitude NUMERIC(9, 6) NOT NULL CHECK (departure_longitude BETWEEN -180 AND 180),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX courier_profile_active_idx ON courier_profile(active);
CREATE INDEX courier_profile_vehicle_type_idx ON courier_profile(vehicle_type);
