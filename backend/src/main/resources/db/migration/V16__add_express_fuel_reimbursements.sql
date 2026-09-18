ALTER TABLE operational_settings
    ADD COLUMN default_fuel_price_per_liter_ariary BIGINT NOT NULL DEFAULT 5000;
ALTER TABLE operational_settings
    ADD COLUMN default_express_return_to_pickup BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE operational_settings
    ADD CONSTRAINT operational_settings_fuel_price_check
        CHECK (default_fuel_price_per_liter_ariary > 0);

ALTER TABLE courier_profile
    ADD COLUMN fuel_reimbursement_enabled_override BOOLEAN;
ALTER TABLE courier_profile
    ADD COLUMN fuel_efficiency_km_per_liter_override NUMERIC(10, 3);
ALTER TABLE courier_profile
    ADD COLUMN fuel_price_per_liter_ariary_override BIGINT;
ALTER TABLE courier_profile
    ADD CONSTRAINT courier_profile_fuel_efficiency_check
        CHECK (fuel_efficiency_km_per_liter_override IS NULL
               OR fuel_efficiency_km_per_liter_override > 0);
ALTER TABLE courier_profile
    ADD CONSTRAINT courier_profile_fuel_price_check
        CHECK (fuel_price_per_liter_ariary_override IS NULL
               OR fuel_price_per_liter_ariary_override > 0);

ALTER TABLE delivery_request
    ADD COLUMN express_return_to_pickup BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE delivery_request
    ADD COLUMN express_return_to_pickup_overridden BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE express_fuel_calculation (
    delivery_request_id UUID PRIMARY KEY REFERENCES delivery_request(id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL DEFAULT 'WAITING_FOR_OUTCOMES'
        CHECK (status IN ('WAITING_FOR_OUTCOMES', 'CALCULATED', 'ROUTING_FAILED',
                          'CONFIGURATION_MISSING', 'MANUAL')),
    return_to_pickup BOOLEAN NOT NULL,
    failure_reason VARCHAR(1000),
    total_distance_meters BIGINT NOT NULL DEFAULT 0 CHECK (total_distance_meters >= 0),
    total_suggested_fuel_ariary BIGINT NOT NULL DEFAULT 0 CHECK (total_suggested_fuel_ariary >= 0),
    total_final_fuel_ariary BIGINT NOT NULL DEFAULT 0 CHECK (total_final_fuel_ariary >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    calculated_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT express_fuel_failure_check CHECK (
        (status IN ('ROUTING_FAILED', 'CONFIGURATION_MISSING') AND failure_reason IS NOT NULL)
        OR (status NOT IN ('ROUTING_FAILED', 'CONFIGURATION_MISSING') AND failure_reason IS NULL)
    )
);

CREATE TABLE express_fuel_courier_calculation (
    delivery_request_id UUID NOT NULL REFERENCES express_fuel_calculation(delivery_request_id) ON DELETE CASCADE,
    courier_id UUID NOT NULL REFERENCES courier_profile(id),
    courier_name_snapshot VARCHAR(150) NOT NULL,
    courier_contact_snapshot VARCHAR(100),
    package_ids_json TEXT NOT NULL,
    automatic_distance_meters BIGINT CHECK (automatic_distance_meters IS NULL OR automatic_distance_meters >= 0),
    final_distance_meters BIGINT NOT NULL CHECK (final_distance_meters >= 0),
    fuel_reimbursement_enabled BOOLEAN NOT NULL,
    fuel_efficiency_km_per_liter NUMERIC(10, 3),
    fuel_price_per_liter_ariary BIGINT NOT NULL CHECK (fuel_price_per_liter_ariary > 0),
    suggested_fuel_ariary BIGINT NOT NULL CHECK (suggested_fuel_ariary >= 0),
    final_fuel_ariary BIGINT NOT NULL CHECK (final_fuel_ariary >= 0),
    override_reason VARCHAR(1000),
    manual BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (delivery_request_id, courier_id),
    CONSTRAINT express_fuel_line_efficiency_check CHECK (
        (fuel_reimbursement_enabled AND fuel_efficiency_km_per_liter IS NOT NULL
             AND fuel_efficiency_km_per_liter > 0)
        OR (NOT fuel_reimbursement_enabled)
    ),
    CONSTRAINT express_fuel_line_override_check CHECK (
        (NOT manual AND final_distance_meters = automatic_distance_meters
             AND final_fuel_ariary = suggested_fuel_ariary AND override_reason IS NULL)
        OR (manual AND override_reason IS NOT NULL)
    )
);

CREATE INDEX express_fuel_calculation_status_idx
    ON express_fuel_calculation(status, updated_at);

ALTER TABLE courier_compensation_batch
    ADD COLUMN total_suggested_commission_ariary BIGINT NOT NULL DEFAULT 0;
ALTER TABLE courier_compensation_batch
    ADD COLUMN total_final_commission_ariary BIGINT NOT NULL DEFAULT 0;
ALTER TABLE courier_compensation_batch
    ADD COLUMN total_suggested_fuel_ariary BIGINT NOT NULL DEFAULT 0;
ALTER TABLE courier_compensation_batch
    ADD COLUMN total_final_fuel_ariary BIGINT NOT NULL DEFAULT 0;

UPDATE courier_compensation_batch
SET total_suggested_commission_ariary = total_suggested_ariary,
    total_final_commission_ariary = total_final_ariary;

ALTER TABLE courier_compensation_batch
    ADD CONSTRAINT courier_compensation_batch_components_check CHECK (
        total_suggested_ariary = total_suggested_commission_ariary + total_suggested_fuel_ariary
        AND total_final_ariary = total_final_commission_ariary + total_final_fuel_ariary
    );

CREATE TABLE courier_fuel_earning (
    id UUID PRIMARY KEY,
    service_date DATE NOT NULL,
    closure_revision INTEGER NOT NULL,
    delivery_request_id UUID NOT NULL REFERENCES delivery_request(id),
    courier_id UUID NOT NULL REFERENCES courier_profile(id),
    courier_name VARCHAR(150) NOT NULL,
    courier_contact VARCHAR(100),
    return_to_pickup BOOLEAN NOT NULL,
    package_ids_json TEXT NOT NULL,
    automatic_distance_meters BIGINT,
    final_distance_meters BIGINT NOT NULL CHECK (final_distance_meters >= 0),
    fuel_reimbursement_enabled BOOLEAN NOT NULL,
    fuel_efficiency_km_per_liter NUMERIC(10, 3),
    fuel_price_per_liter_ariary BIGINT NOT NULL CHECK (fuel_price_per_liter_ariary > 0),
    suggested_amount_ariary BIGINT NOT NULL CHECK (suggested_amount_ariary >= 0),
    final_amount_ariary BIGINT NOT NULL CHECK (final_amount_ariary >= 0),
    override_reason VARCHAR(1000),
    manual BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT courier_fuel_earning_batch_fk
        FOREIGN KEY (service_date, closure_revision)
        REFERENCES courier_compensation_batch(service_date, closure_revision),
    CONSTRAINT courier_fuel_earning_request_courier_uk
        UNIQUE (service_date, closure_revision, delivery_request_id, courier_id)
);

CREATE INDEX courier_fuel_earning_courier_date_idx
    ON courier_fuel_earning(courier_id, service_date DESC);
