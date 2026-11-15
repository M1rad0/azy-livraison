ALTER TABLE delivery_package ADD COLUMN desired_delivery_end_time TIME;
ALTER TABLE delivery_package ADD COLUMN delivery_time_preference VARCHAR(20);
UPDATE delivery_package
SET desired_delivery_end_time = desired_delivery_time + INTERVAL '1' HOUR,
    delivery_time_preference = CASE WHEN desired_delivery_time IS NULL THEN 'NONE' ELSE 'CUSTOM' END;
ALTER TABLE delivery_package ALTER COLUMN delivery_time_preference SET NOT NULL;
ALTER TABLE delivery_package ALTER COLUMN delivery_time_preference SET DEFAULT 'NONE';
ALTER TABLE delivery_package ADD CONSTRAINT delivery_package_time_preference_check
    CHECK (delivery_time_preference IN ('NONE', 'CUSTOM', 'MORNING', 'AFTERNOON'));

ALTER TABLE dispatch_package_outcome ADD COLUMN desired_delivery_end_time TIME;
ALTER TABLE dispatch_package_outcome ADD COLUMN delivery_time_preference VARCHAR(20);
UPDATE dispatch_package_outcome
SET desired_delivery_end_time = desired_delivery_time + INTERVAL '1' HOUR,
    delivery_time_preference = CASE WHEN desired_delivery_time IS NULL THEN 'NONE' ELSE 'CUSTOM' END;
ALTER TABLE dispatch_package_outcome ALTER COLUMN delivery_time_preference SET NOT NULL;
ALTER TABLE dispatch_package_outcome ALTER COLUMN delivery_time_preference SET DEFAULT 'NONE';
ALTER TABLE dispatch_package_outcome ADD CONSTRAINT dispatch_package_time_preference_check
    CHECK (delivery_time_preference IN ('NONE', 'CUSTOM', 'MORNING', 'AFTERNOON'));
