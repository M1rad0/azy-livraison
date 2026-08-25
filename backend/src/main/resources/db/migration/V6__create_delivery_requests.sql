CREATE TABLE delivery_request (
    id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL REFERENCES app_account(id),
    requested_date DATE NOT NULL,
    pickup_label VARCHAR(200) NOT NULL,
    pickup_query VARCHAR(500),
    pickup_latitude DOUBLE PRECISION NOT NULL CHECK (pickup_latitude BETWEEN -90 AND 90),
    pickup_longitude DOUBLE PRECISION NOT NULL CHECK (pickup_longitude BETWEEN -180 AND 180),
    capacity_limit_snapshot INTEGER NOT NULL CHECK (capacity_limit_snapshot > 0),
    capacity_current_snapshot INTEGER NOT NULL CHECK (capacity_current_snapshot >= 0),
    capacity_requested_snapshot INTEGER NOT NULL CHECK (capacity_requested_snapshot > 0),
    capacity_projected_snapshot INTEGER NOT NULL CHECK (capacity_projected_snapshot >= capacity_requested_snapshot),
    capacity_overflow_warning BOOLEAN NOT NULL DEFAULT FALSE,
    capacity_overflow_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PARTIALLY_DISPATCHED', 'DISPATCHED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE delivery_package (
    id UUID PRIMARY KEY,
    delivery_request_id UUID NOT NULL REFERENCES delivery_request(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL CHECK (sequence_number > 0),
    recipient_name VARCHAR(200) NOT NULL,
    faritra BOOLEAN NOT NULL,
    destination_label VARCHAR(200) NOT NULL,
    destination_query VARCHAR(500),
    destination_latitude DOUBLE PRECISION NOT NULL CHECK (destination_latitude BETWEEN -90 AND 90),
    destination_longitude DOUBLE PRECISION NOT NULL CHECK (destination_longitude BETWEEN -180 AND 180),
    contact VARCHAR(100) NOT NULL,
    amount_to_collect_ariary BIGINT NOT NULL CHECK (amount_to_collect_ariary >= 0),
    mobile_money_paid BOOLEAN NOT NULL,
    effective_weight_kg NUMERIC(10, 3) NOT NULL CHECK (effective_weight_kg > 0),
    weight_source VARCHAR(20) NOT NULL CHECK (weight_source IN ('PROVIDED', 'DEFAULT_SETTING')),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'DISPATCHED', 'UNASSIGNED')),
    unassigned_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT delivery_package_request_sequence_uk UNIQUE (delivery_request_id, sequence_number)
);

CREATE TABLE delivery_event (
    id UUID PRIMARY KEY,
    delivery_request_id UUID NOT NULL REFERENCES delivery_request(id) ON DELETE CASCADE,
    delivery_package_id UUID REFERENCES delivery_package(id) ON DELETE CASCADE,
    event_type VARCHAR(80) NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30),
    actor_account_id UUID REFERENCES app_account(id),
    payload_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX delivery_request_owner_date_idx
    ON delivery_request(owner_account_id, requested_date, created_at);
CREATE INDEX delivery_request_date_status_idx
    ON delivery_request(requested_date, status, created_at);
CREATE INDEX delivery_package_request_status_idx
    ON delivery_package(delivery_request_id, status, sequence_number);
CREATE INDEX delivery_event_request_created_idx
    ON delivery_event(delivery_request_id, created_at);
