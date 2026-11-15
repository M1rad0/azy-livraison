CREATE TABLE manual_dispatch_draft (
    id UUID PRIMARY KEY,
    service_date DATE NOT NULL REFERENCES delivery_operation_day(service_date),
    active_service_date DATE UNIQUE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'FINALIZED', 'CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0,
    actor_account_id UUID NOT NULL REFERENCES app_account(id),
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    snapshot_json TEXT NOT NULL,
    final_plan_id UUID REFERENCES dispatch_plan(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT manual_dispatch_actor_key_uk UNIQUE (actor_account_id, idempotency_key),
    CONSTRAINT manual_dispatch_active_check CHECK (
        (status = 'DRAFT' AND active_service_date = service_date AND active_service_date IS NOT NULL)
        OR (status <> 'DRAFT' AND active_service_date IS NULL)
    )
);

CREATE INDEX manual_dispatch_final_plan_idx ON manual_dispatch_draft(final_plan_id);

-- A manual sequence has no measured route yet; unknown is not a zero-kilometre trip.
ALTER TABLE dispatch_stop ALTER COLUMN distance_from_previous_meters DROP NOT NULL;
ALTER TABLE dispatch_stop ALTER COLUMN duration_from_previous_seconds DROP NOT NULL;
ALTER TABLE dispatch_stop ALTER COLUMN cumulative_distance_meters DROP NOT NULL;
ALTER TABLE dispatch_stop ALTER COLUMN cumulative_duration_seconds DROP NOT NULL;
