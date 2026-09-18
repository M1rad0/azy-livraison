ALTER TABLE delivery_pricing_policy
    ADD COLUMN distance_mode VARCHAR(20) NOT NULL DEFAULT 'AIR_DISTANCE';

ALTER TABLE delivery_pricing_policy
    ADD CONSTRAINT delivery_pricing_policy_distance_mode_check
        CHECK (distance_mode IN ('AIR_DISTANCE', 'ROAD_DISTANCE'));

ALTER TABLE delivery_package
    ADD COLUMN pricing_distance_mode VARCHAR(20);

UPDATE delivery_package
SET pricing_distance_mode = 'AIR_DISTANCE'
WHERE pricing_zone_id IS NOT NULL;

ALTER TABLE delivery_package
    ADD CONSTRAINT delivery_package_pricing_distance_mode_check
        CHECK (pricing_distance_mode IS NULL
            OR pricing_distance_mode IN ('AIR_DISTANCE', 'ROAD_DISTANCE'));

ALTER TABLE delivery_package
    ADD CONSTRAINT delivery_package_pricing_distance_snapshot_check CHECK (
        (pricing_zone_id IS NULL AND pricing_distance_mode IS NULL)
        OR (pricing_zone_id IS NOT NULL AND pricing_distance_mode IS NOT NULL)
    );

ALTER TABLE dispatch_package_outcome
    ADD COLUMN pricing_distance_mode VARCHAR(20);

UPDATE dispatch_package_outcome
SET pricing_distance_mode = 'AIR_DISTANCE'
WHERE pricing_zone_id IS NOT NULL;

ALTER TABLE dispatch_package_outcome
    ADD CONSTRAINT dispatch_package_pricing_distance_mode_check
        CHECK (pricing_distance_mode IS NULL
            OR pricing_distance_mode IN ('AIR_DISTANCE', 'ROAD_DISTANCE'));

ALTER TABLE dispatch_package_outcome
    ADD CONSTRAINT dispatch_package_pricing_distance_snapshot_check CHECK (
        (pricing_zone_id IS NULL AND pricing_distance_mode IS NULL)
        OR (pricing_zone_id IS NOT NULL AND pricing_distance_mode IS NOT NULL)
    );
