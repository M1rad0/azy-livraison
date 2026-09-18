ALTER TABLE dispatch_package_outcome
    ADD COLUMN desired_delivery_time TIME;

ALTER TABLE dispatch_package_outcome
    ADD COLUMN pricing_policy_id SMALLINT;

ALTER TABLE dispatch_package_outcome
    ADD COLUMN base_delivery_fee_ariary BIGINT
        CHECK (base_delivery_fee_ariary IS NULL OR base_delivery_fee_ariary >= 0);

ALTER TABLE dispatch_package_outcome
    ADD COLUMN weight_surcharge_ariary BIGINT
        CHECK (weight_surcharge_ariary IS NULL OR weight_surcharge_ariary >= 0);

ALTER TABLE dispatch_package_outcome
    ADD COLUMN time_surcharge_ariary BIGINT
        CHECK (time_surcharge_ariary IS NULL OR time_surcharge_ariary >= 0);

-- Keep historical rows readable when their source package predates advanced
-- pricing. Correlated subqueries work on PostgreSQL and on the H2 test schema.
UPDATE dispatch_package_outcome
SET desired_delivery_time = (
        SELECT delivery_package.desired_delivery_time
        FROM delivery_package
        WHERE delivery_package.id = dispatch_package_outcome.delivery_package_id
    ),
    pricing_policy_id = (
        SELECT delivery_package.pricing_policy_id
        FROM delivery_package
        WHERE delivery_package.id = dispatch_package_outcome.delivery_package_id
    ),
    base_delivery_fee_ariary = (
        SELECT delivery_package.base_delivery_fee_ariary
        FROM delivery_package
        WHERE delivery_package.id = dispatch_package_outcome.delivery_package_id
    ),
    weight_surcharge_ariary = (
        SELECT delivery_package.weight_surcharge_ariary
        FROM delivery_package
        WHERE delivery_package.id = dispatch_package_outcome.delivery_package_id
    ),
    time_surcharge_ariary = (
        SELECT delivery_package.time_surcharge_ariary
        FROM delivery_package
        WHERE delivery_package.id = dispatch_package_outcome.delivery_package_id
    );
