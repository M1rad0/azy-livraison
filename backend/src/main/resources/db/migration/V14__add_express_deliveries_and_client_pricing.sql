ALTER TABLE client_profile
    ADD COLUMN express_delivery_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE delivery_request
    ADD COLUMN request_kind VARCHAR(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE delivery_request
    ADD COLUMN express_status VARCHAR(20);
ALTER TABLE delivery_request
    ADD COLUMN created_by_account_id UUID REFERENCES app_account(id);
ALTER TABLE delivery_request
    ADD COLUMN cancelled_before_processing_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery_request
    ADD COLUMN cancelled_before_processing_by_account_id UUID REFERENCES app_account(id);
ALTER TABLE delivery_request
    ADD CONSTRAINT delivery_request_kind_check
        CHECK (request_kind IN ('NORMAL', 'EXPRESS'));
ALTER TABLE delivery_request
    ADD CONSTRAINT delivery_request_express_status_check
        CHECK (express_status IS NULL OR express_status IN ('PENDING', 'CONFIRMED', 'COMPLETED'));
ALTER TABLE delivery_request
    ADD CONSTRAINT delivery_request_express_consistency_check CHECK (
        (request_kind = 'NORMAL' AND express_status IS NULL)
        OR (request_kind = 'EXPRESS' AND express_status IS NOT NULL)
    );

UPDATE delivery_request
SET created_by_account_id = owner_account_id
WHERE created_by_account_id IS NULL;
ALTER TABLE delivery_request ALTER COLUMN created_by_account_id SET NOT NULL;

ALTER TABLE delivery_package
    ADD COLUMN desired_delivery_time TIME;
ALTER TABLE delivery_package
    ADD COLUMN cancelled_before_processing_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery_package
    ADD COLUMN cancelled_before_processing_by_account_id UUID REFERENCES app_account(id);
ALTER TABLE delivery_package
    ADD COLUMN pricing_policy_id SMALLINT;
ALTER TABLE delivery_package
    ADD COLUMN base_delivery_fee_ariary BIGINT CHECK (base_delivery_fee_ariary IS NULL OR base_delivery_fee_ariary >= 0);
ALTER TABLE delivery_package
    ADD COLUMN weight_surcharge_ariary BIGINT CHECK (weight_surcharge_ariary IS NULL OR weight_surcharge_ariary >= 0);
ALTER TABLE delivery_package
    ADD COLUMN time_surcharge_ariary BIGINT CHECK (time_surcharge_ariary IS NULL OR time_surcharge_ariary >= 0);

UPDATE delivery_package
SET pricing_policy_id = 1,
    base_delivery_fee_ariary = delivery_fee_ariary,
    weight_surcharge_ariary = 0,
    time_surcharge_ariary = 0
WHERE delivery_fee_ariary IS NOT NULL;

ALTER TABLE delivery_pricing_policy DROP CONSTRAINT delivery_pricing_policy_id_check;
ALTER TABLE delivery_pricing_policy
    ADD COLUMN client_account_id UUID REFERENCES app_account(id) ON DELETE CASCADE;
ALTER TABLE delivery_pricing_policy
    ADD COLUMN request_kind VARCHAR(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE delivery_pricing_policy
    ADD COLUMN default_weight_kg NUMERIC(10, 3) NOT NULL DEFAULT 1 CHECK (default_weight_kg > 0);
ALTER TABLE delivery_pricing_policy
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE delivery_pricing_policy
    ADD CONSTRAINT delivery_pricing_policy_kind_check CHECK (request_kind IN ('NORMAL', 'EXPRESS'));

CREATE UNIQUE INDEX delivery_pricing_default_kind_uk
    ON delivery_pricing_policy(request_kind)
    WHERE client_account_id IS NULL AND active;
CREATE UNIQUE INDEX delivery_pricing_client_kind_uk
    ON delivery_pricing_policy(client_account_id, request_kind)
    WHERE client_account_id IS NOT NULL AND active;

INSERT INTO delivery_pricing_policy (
    id, center_label, center_query, center_latitude, center_longitude,
    client_account_id, request_kind, default_weight_kg, active, version
)
SELECT 2, center_label, center_query, center_latitude, center_longitude,
       NULL, 'EXPRESS', default_weight_kg, TRUE, 0
FROM delivery_pricing_policy
WHERE id = 1;

INSERT INTO delivery_pricing_zone (
    id, pricing_policy_id, zone_order, name, max_distance_meters, delivery_fee_ariary
)
SELECT CASE zone_order
           WHEN 1 THEN '72000000-0000-0000-0000-000000000001'::UUID
           WHEN 2 THEN '72000000-0000-0000-0000-000000000002'::UUID
           WHEN 3 THEN '72000000-0000-0000-0000-000000000003'::UUID
           WHEN 4 THEN '72000000-0000-0000-0000-000000000004'::UUID
           ELSE '72000000-0000-0000-0000-000000000005'::UUID
       END, 2, zone_order, name, max_distance_meters, delivery_fee_ariary
FROM delivery_pricing_zone
WHERE pricing_policy_id = 1;

CREATE TABLE delivery_pricing_weight_tier (
    id UUID PRIMARY KEY,
    pricing_policy_id SMALLINT NOT NULL REFERENCES delivery_pricing_policy(id) ON DELETE CASCADE,
    tier_order INTEGER NOT NULL CHECK (tier_order > 0),
    max_weight_kg NUMERIC(10, 3) NOT NULL CHECK (max_weight_kg > 0),
    surcharge_ariary BIGINT NOT NULL CHECK (surcharge_ariary >= 0),
    CONSTRAINT delivery_pricing_weight_order_uk UNIQUE (pricing_policy_id, tier_order),
    CONSTRAINT delivery_pricing_weight_limit_uk UNIQUE (pricing_policy_id, max_weight_kg)
);

CREATE TABLE delivery_pricing_time_slot (
    id UUID PRIMARY KEY,
    pricing_policy_id SMALLINT NOT NULL REFERENCES delivery_pricing_policy(id) ON DELETE CASCADE,
    slot_order INTEGER NOT NULL CHECK (slot_order > 0),
    label VARCHAR(100) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    surcharge_ariary BIGINT NOT NULL CHECK (surcharge_ariary >= 0),
    CONSTRAINT delivery_pricing_time_order_uk UNIQUE (pricing_policy_id, slot_order),
    CONSTRAINT delivery_pricing_time_range_check CHECK (start_time < end_time)
);

CREATE TABLE express_delivery_courier (
    delivery_request_id UUID NOT NULL REFERENCES delivery_request(id) ON DELETE CASCADE,
    courier_id UUID NOT NULL REFERENCES courier_profile(id),
    courier_name_snapshot VARCHAR(150) NOT NULL,
    selected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    selected_by_account_id UUID NOT NULL REFERENCES app_account(id),
    PRIMARY KEY (delivery_request_id, courier_id)
);

CREATE INDEX delivery_request_kind_date_status_idx
    ON delivery_request(request_kind, requested_date, express_status, created_at);

-- Express requests are intentionally excluded from automatic dispatch.
CREATE INDEX delivery_request_active_express_idx
    ON delivery_request(express_status, created_at)
    WHERE request_kind = 'EXPRESS' AND cancelled_before_processing_at IS NULL;
