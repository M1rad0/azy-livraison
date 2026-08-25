CREATE TABLE delivery_pricing_policy (
    id SMALLINT DEFAULT 1 PRIMARY KEY CHECK (id = 1),
    center_label VARCHAR(200) NOT NULL,
    center_query VARCHAR(500),
    center_latitude DOUBLE PRECISION NOT NULL CHECK (center_latitude BETWEEN -90 AND 90),
    center_longitude DOUBLE PRECISION NOT NULL CHECK (center_longitude BETWEEN -180 AND 180),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE delivery_pricing_zone (
    id UUID PRIMARY KEY,
    pricing_policy_id SMALLINT NOT NULL REFERENCES delivery_pricing_policy(id) ON DELETE CASCADE,
    zone_order INTEGER NOT NULL CHECK (zone_order > 0),
    name VARCHAR(100) NOT NULL,
    max_distance_meters BIGINT NOT NULL CHECK (max_distance_meters > 0),
    delivery_fee_ariary BIGINT NOT NULL CHECK (delivery_fee_ariary >= 0),
    CONSTRAINT delivery_pricing_zone_order_uk UNIQUE (pricing_policy_id, zone_order),
    CONSTRAINT delivery_pricing_zone_name_uk UNIQUE (pricing_policy_id, name),
    CONSTRAINT delivery_pricing_zone_radius_uk UNIQUE (pricing_policy_id, max_distance_meters)
);

INSERT INTO delivery_pricing_policy (
    id, center_label, center_query, center_latitude, center_longitude
) VALUES (
    1, 'Antsahavola–Antaninarenina',
    'Antsahavola, Antaninarenina, Antananarivo, Madagascar', -18.909600, 47.523600
);

INSERT INTO delivery_pricing_zone (
    id, pricing_policy_id, zone_order, name, max_distance_meters, delivery_fee_ariary
) VALUES
    ('71000000-0000-0000-0000-000000000001', 1, 1, 'Centre-ville',   2500,  3000),
    ('71000000-0000-0000-0000-000000000002', 1, 2, 'Périphérie',    4500,  4000),
    ('71000000-0000-0000-0000-000000000003', 1, 3, 'Périphérie+',   8000,  5000),
    ('71000000-0000-0000-0000-000000000004', 1, 4, 'Périphérie++', 10500,  6000),
    ('71000000-0000-0000-0000-000000000005', 1, 5, 'Périphérie+++',16500,  8000);

ALTER TABLE delivery_package ADD COLUMN pricing_zone_id UUID;
ALTER TABLE delivery_package ADD COLUMN pricing_zone_name VARCHAR(100);
ALTER TABLE delivery_package ADD COLUMN delivery_fee_ariary BIGINT CHECK (delivery_fee_ariary >= 0);
ALTER TABLE delivery_package ADD COLUMN distance_from_center_meters BIGINT CHECK (distance_from_center_meters >= 0);
ALTER TABLE delivery_package ADD COLUMN pricing_zone_max_distance_meters BIGINT
    CHECK (pricing_zone_max_distance_meters > 0);
ALTER TABLE delivery_package ADD COLUMN pricing_policy_version BIGINT CHECK (pricing_policy_version >= 0);
CREATE INDEX delivery_package_pricing_zone_idx ON delivery_package(pricing_zone_id);
ALTER TABLE delivery_package ADD CONSTRAINT delivery_package_pricing_snapshot_check CHECK (
        (pricing_zone_id IS NULL
            AND pricing_zone_name IS NULL
            AND delivery_fee_ariary IS NULL
            AND distance_from_center_meters IS NULL
            AND pricing_zone_max_distance_meters IS NULL
            AND pricing_policy_version IS NULL)
        OR
        (pricing_zone_id IS NOT NULL
            AND pricing_zone_name IS NOT NULL
            AND delivery_fee_ariary IS NOT NULL
            AND distance_from_center_meters IS NOT NULL
            AND pricing_zone_max_distance_meters IS NOT NULL
            AND pricing_policy_version IS NOT NULL)
    );

ALTER TABLE dispatch_package_outcome ADD COLUMN pricing_zone_id UUID;
ALTER TABLE dispatch_package_outcome ADD COLUMN pricing_zone_name VARCHAR(100);
ALTER TABLE dispatch_package_outcome ADD COLUMN delivery_fee_ariary BIGINT CHECK (delivery_fee_ariary >= 0);
ALTER TABLE dispatch_package_outcome ADD COLUMN distance_from_center_meters BIGINT
    CHECK (distance_from_center_meters >= 0);
ALTER TABLE dispatch_package_outcome ADD COLUMN pricing_zone_max_distance_meters BIGINT
    CHECK (pricing_zone_max_distance_meters > 0);
ALTER TABLE dispatch_package_outcome ADD COLUMN pricing_policy_version BIGINT CHECK (pricing_policy_version >= 0);
CREATE INDEX dispatch_package_outcome_pricing_zone_idx ON dispatch_package_outcome(pricing_zone_id);
ALTER TABLE dispatch_package_outcome ADD CONSTRAINT dispatch_package_pricing_snapshot_check CHECK (
        (pricing_zone_id IS NULL
            AND pricing_zone_name IS NULL
            AND delivery_fee_ariary IS NULL
            AND distance_from_center_meters IS NULL
            AND pricing_zone_max_distance_meters IS NULL
            AND pricing_policy_version IS NULL)
        OR
        (pricing_zone_id IS NOT NULL
            AND pricing_zone_name IS NOT NULL
            AND delivery_fee_ariary IS NOT NULL
            AND distance_from_center_meters IS NOT NULL
            AND pricing_zone_max_distance_meters IS NOT NULL
            AND pricing_policy_version IS NOT NULL)
    );
