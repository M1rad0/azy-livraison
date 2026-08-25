ALTER TABLE operational_settings
    ADD COLUMN default_courier_commission_numerator INTEGER NOT NULL DEFAULT 1;
ALTER TABLE operational_settings
    ADD COLUMN default_courier_commission_denominator INTEGER NOT NULL DEFAULT 3;
ALTER TABLE operational_settings
    ADD CONSTRAINT operational_settings_commission_fraction_check CHECK (
        default_courier_commission_numerator > 0
        AND default_courier_commission_denominator > 0
        AND default_courier_commission_numerator <= default_courier_commission_denominator
    );

ALTER TABLE courier_profile ADD COLUMN commission_numerator_override INTEGER;
ALTER TABLE courier_profile ADD COLUMN commission_denominator_override INTEGER;
ALTER TABLE courier_profile
    ADD CONSTRAINT courier_profile_commission_fraction_check CHECK (
        (commission_numerator_override IS NULL AND commission_denominator_override IS NULL)
        OR (
            commission_numerator_override IS NOT NULL
            AND commission_denominator_override IS NOT NULL
            AND commission_numerator_override > 0
            AND commission_denominator_override > 0
            AND commission_numerator_override <= commission_denominator_override
        )
    );

CREATE TABLE client_daily_settlement (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL UNIQUE,
    service_date DATE NOT NULL,
    closure_revision INTEGER NOT NULL CHECK (closure_revision > 0),
    client_account_id UUID NOT NULL REFERENCES app_account(id),
    client_display_name VARCHAR(150) NOT NULL,
    amount_due_ariary BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT client_daily_settlement_report_uk
        UNIQUE (service_date, closure_revision, client_account_id),
    CONSTRAINT client_daily_settlement_snapshot_fk
        FOREIGN KEY (service_date, closure_revision, client_account_id)
        REFERENCES delivery_operation_client_snapshot(service_date, closure_revision, account_id)
);

CREATE INDEX client_daily_settlement_client_date_idx
    ON client_daily_settlement(client_account_id, service_date DESC);

CREATE TABLE client_settlement_payment (
    id UUID PRIMARY KEY,
    settlement_id UUID NOT NULL REFERENCES client_daily_settlement(id),
    direction VARCHAR(30) NOT NULL
        CHECK (direction IN ('AZY_TO_CLIENT', 'CLIENT_TO_AZY')),
    amount_ariary BIGINT NOT NULL CHECK (amount_ariary > 0),
    payment_method VARCHAR(80) NOT NULL,
    destination_operator VARCHAR(80),
    destination_account_holder VARCHAR(150),
    destination_number VARCHAR(100),
    transaction_reference VARCHAR(200),
    note VARCHAR(1000),
    proof_storage_key VARCHAR(200),
    proof_original_filename VARCHAR(255),
    proof_content_type VARCHAR(100),
    proof_size_bytes BIGINT,
    proof_sha256 CHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (status IN ('CONFIRMED', 'VOIDED')),
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_by_account_id UUID NOT NULL REFERENCES app_account(id),
    voided_at TIMESTAMP WITH TIME ZONE,
    voided_by_account_id UUID REFERENCES app_account(id),
    void_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT client_payment_destination_check CHECK (
        (destination_operator IS NULL AND destination_account_holder IS NULL AND destination_number IS NULL)
        OR (destination_operator IS NOT NULL AND destination_account_holder IS NOT NULL AND destination_number IS NOT NULL)
    ),
    CONSTRAINT client_payment_proof_check CHECK (
        (proof_storage_key IS NULL AND proof_original_filename IS NULL AND proof_content_type IS NULL
            AND proof_size_bytes IS NULL AND proof_sha256 IS NULL)
        OR (proof_storage_key IS NOT NULL AND proof_original_filename IS NOT NULL AND proof_content_type IS NOT NULL
            AND proof_size_bytes IS NOT NULL AND proof_size_bytes > 0 AND proof_sha256 IS NOT NULL)
    ),
    CONSTRAINT client_payment_void_check CHECK (
        (status = 'CONFIRMED' AND voided_at IS NULL AND voided_by_account_id IS NULL AND void_reason IS NULL)
        OR (status = 'VOIDED' AND voided_at IS NOT NULL AND voided_by_account_id IS NOT NULL AND void_reason IS NOT NULL)
    )
);

CREATE INDEX client_settlement_payment_settlement_idx
    ON client_settlement_payment(settlement_id, confirmed_at DESC);

CREATE TABLE courier_compensation_batch (
    service_date DATE NOT NULL REFERENCES delivery_operation_day(service_date),
    closure_revision INTEGER NOT NULL CHECK (closure_revision > 0),
    total_suggested_ariary BIGINT NOT NULL CHECK (total_suggested_ariary >= 0),
    total_final_ariary BIGINT NOT NULL CHECK (total_final_ariary >= 0),
    finalized_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalized_by_account_id UUID NOT NULL REFERENCES app_account(id),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (service_date, closure_revision)
);

CREATE TABLE courier_earning (
    id UUID PRIMARY KEY,
    service_date DATE NOT NULL,
    closure_revision INTEGER NOT NULL,
    delivery_package_id UUID NOT NULL REFERENCES delivery_package(id),
    courier_id UUID NOT NULL REFERENCES courier_profile(id),
    courier_name VARCHAR(150) NOT NULL,
    courier_contact VARCHAR(100),
    delivery_outcome VARCHAR(30) NOT NULL
        CHECK (delivery_outcome IN ('DELIVERED', 'RETURN_TO_SENDER')),
    delivery_fee_ariary BIGINT NOT NULL CHECK (delivery_fee_ariary >= 0),
    commission_numerator INTEGER NOT NULL CHECK (commission_numerator > 0),
    commission_denominator INTEGER NOT NULL CHECK (commission_denominator > 0),
    suggested_amount_ariary BIGINT NOT NULL CHECK (suggested_amount_ariary >= 0),
    final_amount_ariary BIGINT NOT NULL CHECK (final_amount_ariary >= 0),
    override_reason VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT courier_earning_batch_fk
        FOREIGN KEY (service_date, closure_revision)
        REFERENCES courier_compensation_batch(service_date, closure_revision),
    CONSTRAINT courier_earning_package_uk UNIQUE (delivery_package_id),
    CONSTRAINT courier_earning_fraction_check
        CHECK (commission_numerator <= commission_denominator),
    CONSTRAINT courier_earning_override_check CHECK (
        (final_amount_ariary = suggested_amount_ariary AND override_reason IS NULL)
        OR (final_amount_ariary <> suggested_amount_ariary AND override_reason IS NOT NULL)
    )
);

CREATE INDEX courier_earning_courier_date_idx
    ON courier_earning(courier_id, service_date DESC);

CREATE TABLE courier_payment (
    id UUID PRIMARY KEY,
    courier_id UUID NOT NULL REFERENCES courier_profile(id),
    amount_ariary BIGINT NOT NULL CHECK (amount_ariary > 0),
    payment_method VARCHAR(80) NOT NULL,
    destination_operator VARCHAR(80),
    destination_account_holder VARCHAR(150),
    destination_number VARCHAR(100),
    transaction_reference VARCHAR(200),
    note VARCHAR(1000),
    proof_storage_key VARCHAR(200),
    proof_original_filename VARCHAR(255),
    proof_content_type VARCHAR(100),
    proof_size_bytes BIGINT,
    proof_sha256 CHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (status IN ('CONFIRMED', 'VOIDED')),
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_by_account_id UUID NOT NULL REFERENCES app_account(id),
    voided_at TIMESTAMP WITH TIME ZONE,
    voided_by_account_id UUID REFERENCES app_account(id),
    void_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT courier_payment_destination_check CHECK (
        (destination_operator IS NULL AND destination_account_holder IS NULL AND destination_number IS NULL)
        OR (destination_operator IS NOT NULL AND destination_account_holder IS NOT NULL AND destination_number IS NOT NULL)
    ),
    CONSTRAINT courier_payment_proof_check CHECK (
        (proof_storage_key IS NULL AND proof_original_filename IS NULL AND proof_content_type IS NULL
            AND proof_size_bytes IS NULL AND proof_sha256 IS NULL)
        OR (proof_storage_key IS NOT NULL AND proof_original_filename IS NOT NULL AND proof_content_type IS NOT NULL
            AND proof_size_bytes IS NOT NULL AND proof_size_bytes > 0 AND proof_sha256 IS NOT NULL)
    ),
    CONSTRAINT courier_payment_void_check CHECK (
        (status = 'CONFIRMED' AND voided_at IS NULL AND voided_by_account_id IS NULL AND void_reason IS NULL)
        OR (status = 'VOIDED' AND voided_at IS NOT NULL AND voided_by_account_id IS NOT NULL AND void_reason IS NOT NULL)
    )
);

CREATE INDEX courier_payment_courier_date_idx
    ON courier_payment(courier_id, confirmed_at DESC);
