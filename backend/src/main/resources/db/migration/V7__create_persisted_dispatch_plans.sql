CREATE TABLE dispatch_plan (
    id UUID PRIMARY KEY,
    service_date DATE NOT NULL,
    status VARCHAR(40) NOT NULL
        CHECK (status IN ('GENERATING', 'READY', 'READY_WITH_WARNINGS', 'FAILED')),
    strategy VARCHAR(30) NOT NULL DEFAULT 'VIA_HUB'
        CHECK (strategy = 'VIA_HUB'),
    created_by_account_id UUID NOT NULL REFERENCES app_account(id),
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    hub_label VARCHAR(200) NOT NULL,
    hub_query VARCHAR(500),
    hub_latitude DOUBLE PRECISION NOT NULL CHECK (hub_latitude BETWEEN -90 AND 90),
    hub_longitude DOUBLE PRECISION NOT NULL CHECK (hub_longitude BETWEEN -180 AND 180),
    input_snapshot_json TEXT NOT NULL,
    policy_snapshot_json TEXT NOT NULL,
    result_json TEXT,
    hub_execution_json TEXT,
    metrics_json TEXT,
    result_status VARCHAR(40),
    solver_status VARCHAR(100),
    selected_package_count INTEGER NOT NULL CHECK (selected_package_count > 0),
    assigned_package_count INTEGER,
    unassigned_package_count INTEGER,
    selected_courier_count INTEGER NOT NULL CHECK (selected_courier_count > 0),
    used_courier_count INTEGER,
    total_distance_meters BIGINT,
    total_duration_seconds BIGINT,
    meeting_at TIMESTAMP WITH TIME ZONE,
    redistribution_completed_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    failure_code VARCHAR(100),
    failure_message VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_processing_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT dispatch_plan_actor_idempotency_uk UNIQUE (created_by_account_id, idempotency_key)
);

CREATE TABLE dispatch_day_reservation (
    service_date DATE PRIMARY KEY,
    dispatch_plan_id UUID NOT NULL UNIQUE REFERENCES dispatch_plan(id) ON DELETE CASCADE,
    reserved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE dispatch_package_reservation (
    delivery_package_id UUID PRIMARY KEY REFERENCES delivery_package(id) ON DELETE CASCADE,
    dispatch_plan_id UUID NOT NULL REFERENCES dispatch_plan(id) ON DELETE CASCADE,
    reserved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX dispatch_package_reservation_plan_idx
    ON dispatch_package_reservation(dispatch_plan_id);

CREATE TABLE dispatch_plan_courier (
    dispatch_plan_id UUID NOT NULL REFERENCES dispatch_plan(id) ON DELETE CASCADE,
    courier_id UUID NOT NULL REFERENCES courier_profile(id),
    courier_name VARCHAR(150) NOT NULL,
    courier_contact VARCHAR(100),
    vehicle_type VARCHAR(30) NOT NULL,
    max_weight_kg NUMERIC(10, 3) NOT NULL,
    average_speed_kmh NUMERIC(10, 3) NOT NULL,
    departure_at TIMESTAMP WITH TIME ZONE NOT NULL,
    departure_label VARCHAR(200),
    departure_query VARCHAR(500),
    departure_latitude DOUBLE PRECISION NOT NULL,
    departure_longitude DOUBLE PRECISION NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    collection_role BOOLEAN NOT NULL DEFAULT FALSE,
    distribution_role BOOLEAN NOT NULL DEFAULT FALSE,
    hub_arrival_at TIMESTAMP WITH TIME ZONE,
    hub_departure_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    waiting_seconds BIGINT,
    load_at_hub_arrival_kg NUMERIC(10, 3),
    load_at_hub_departure_kg NUMERIC(10, 3),
    route_distance_meters BIGINT,
    route_duration_seconds BIGINT,
    PRIMARY KEY (dispatch_plan_id, courier_id)
);

CREATE TABLE dispatch_package_outcome (
    dispatch_plan_id UUID NOT NULL REFERENCES dispatch_plan(id) ON DELETE CASCADE,
    delivery_package_id UUID NOT NULL REFERENCES delivery_package(id),
    delivery_request_id UUID NOT NULL REFERENCES delivery_request(id),
    outcome VARCHAR(30) NOT NULL CHECK (outcome IN ('ASSIGNED', 'UNASSIGNED')),
    collector_courier_id UUID REFERENCES courier_profile(id),
    distributor_courier_id UUID REFERENCES courier_profile(id),
    transferred BOOLEAN NOT NULL DEFAULT FALSE,
    estimated_delivery_at TIMESTAMP WITH TIME ZONE,
    delivery_order INTEGER,
    unassigned_reason VARCHAR(100),
    unassigned_message VARCHAR(1000),
    recipient_name VARCHAR(200) NOT NULL,
    faritra BOOLEAN NOT NULL,
    contact VARCHAR(100) NOT NULL,
    amount_to_collect_ariary BIGINT NOT NULL,
    mobile_money_paid BOOLEAN NOT NULL,
    effective_weight_kg NUMERIC(10, 3) NOT NULL,
    destination_label VARCHAR(200) NOT NULL,
    destination_query VARCHAR(500),
    destination_latitude DOUBLE PRECISION NOT NULL,
    destination_longitude DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (dispatch_plan_id, delivery_package_id)
);

CREATE INDEX dispatch_package_outcome_distributor_idx
    ON dispatch_package_outcome(dispatch_plan_id, distributor_courier_id, delivery_order);

CREATE TABLE dispatch_stop (
    id UUID PRIMARY KEY,
    dispatch_plan_id UUID NOT NULL REFERENCES dispatch_plan(id) ON DELETE CASCADE,
    courier_id UUID NOT NULL REFERENCES courier_profile(id),
    stop_order INTEGER NOT NULL CHECK (stop_order >= 0),
    stop_type VARCHAR(20) NOT NULL CHECK (stop_type IN ('START', 'PICKUP', 'HUB', 'DELIVERY')),
    delivery_request_id UUID REFERENCES delivery_request(id),
    delivery_package_id UUID REFERENCES delivery_package(id),
    label VARCHAR(200),
    location_label VARCHAR(200),
    location_query VARCHAR(500),
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    estimated_arrival_at TIMESTAMP WITH TIME ZONE,
    distance_from_previous_meters BIGINT NOT NULL DEFAULT 0,
    duration_from_previous_seconds BIGINT NOT NULL DEFAULT 0,
    cumulative_distance_meters BIGINT NOT NULL DEFAULT 0,
    cumulative_duration_seconds BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT dispatch_stop_route_order_uk UNIQUE (dispatch_plan_id, courier_id, stop_order)
);

CREATE INDEX dispatch_plan_service_date_idx ON dispatch_plan(service_date, created_at);
CREATE INDEX dispatch_stop_plan_courier_idx ON dispatch_stop(dispatch_plan_id, courier_id, stop_order);
