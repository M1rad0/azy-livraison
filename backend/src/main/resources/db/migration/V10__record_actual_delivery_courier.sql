ALTER TABLE delivery_package
    ADD COLUMN actual_delivery_courier_id UUID REFERENCES courier_profile(id);
ALTER TABLE delivery_package
    ADD COLUMN actual_delivery_courier_name VARCHAR(150);
ALTER TABLE delivery_package
    ADD COLUMN actual_delivery_courier_contact VARCHAR(100);
ALTER TABLE delivery_package
    ADD COLUMN actual_delivery_vehicle_type VARCHAR(30);

-- A planned assignment is not proof of who actually performed the work.
-- Existing outcomes therefore deliberately remain unknown instead of being
-- backfilled from dispatch_package_outcome.
ALTER TABLE delivery_package
    ADD CONSTRAINT delivery_package_actual_courier_snapshot_check CHECK (
        (
            (actual_delivery_courier_id IS NULL
                AND actual_delivery_courier_name IS NULL
                AND actual_delivery_courier_contact IS NULL
                AND actual_delivery_vehicle_type IS NULL)
            OR (actual_delivery_courier_id IS NOT NULL
                AND actual_delivery_courier_name IS NOT NULL
                AND actual_delivery_vehicle_type IS NOT NULL)
        )
        AND (
            actual_delivery_courier_id IS NULL
            OR (final_outcome IS NOT NULL
                AND final_outcome IN ('DELIVERED', 'RETURN_TO_SENDER'))
        )
    );

CREATE INDEX delivery_package_actual_courier_idx
    ON delivery_package(actual_delivery_courier_id, final_outcome);
