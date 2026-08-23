ALTER TABLE delivery_package ADD COLUMN final_outcome VARCHAR(30);
ALTER TABLE delivery_package ADD COLUMN actual_collected_ariary BIGINT;
ALTER TABLE delivery_package ADD COLUMN outcome_note VARCHAR(1000);
ALTER TABLE delivery_package ADD COLUMN outcome_recorded_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery_package
    ADD COLUMN outcome_recorded_by_account_id UUID REFERENCES app_account(id);
ALTER TABLE delivery_package ADD COLUMN return_completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery_package
    ADD COLUMN return_completed_by_account_id UUID REFERENCES app_account(id);
ALTER TABLE delivery_package
    ADD COLUMN carried_forward_from_package_id UUID REFERENCES delivery_package(id);
ALTER TABLE delivery_package ADD COLUMN dispatch_plan_id UUID REFERENCES dispatch_plan(id);
ALTER TABLE delivery_package ADD COLUMN priority_at TIMESTAMP WITH TIME ZONE;

UPDATE delivery_package
SET priority_at = created_at
WHERE priority_at IS NULL;

ALTER TABLE delivery_package ALTER COLUMN priority_at SET NOT NULL;
ALTER TABLE delivery_package
    ADD CONSTRAINT delivery_package_final_outcome_check CHECK (
        final_outcome IS NULL
        OR final_outcome IN ('DELIVERED', 'CANCELLED', 'POSTPONED', 'RETURN_TO_SENDER')
    );
ALTER TABLE delivery_package
    ADD CONSTRAINT delivery_package_actual_collected_check CHECK (
        actual_collected_ariary IS NULL OR actual_collected_ariary >= 0
    );
ALTER TABLE delivery_package
    ADD CONSTRAINT delivery_package_outcome_consistency_check CHECK (
        (final_outcome IS NULL
            AND actual_collected_ariary IS NULL
            AND outcome_recorded_at IS NULL
            AND outcome_recorded_by_account_id IS NULL)
        OR (final_outcome IS NOT NULL
            AND outcome_recorded_at IS NOT NULL
            AND outcome_recorded_by_account_id IS NOT NULL)
    );
ALTER TABLE delivery_package
    ADD CONSTRAINT delivery_package_return_consistency_check CHECK (
        (return_completed_at IS NULL AND return_completed_by_account_id IS NULL)
        OR (final_outcome = 'RETURN_TO_SENDER'
            AND return_completed_at IS NOT NULL
            AND return_completed_by_account_id IS NOT NULL)
    );
ALTER TABLE delivery_package
    ADD CONSTRAINT delivery_package_carry_forward_uk UNIQUE (carried_forward_from_package_id);

CREATE INDEX delivery_package_daily_workflow_idx
    ON delivery_package(dispatch_plan_id, final_outcome, sequence_number);
CREATE INDEX delivery_package_requested_capacity_idx
    ON delivery_package(delivery_request_id, final_outcome, status);
CREATE INDEX delivery_package_priority_idx
    ON delivery_package(priority_at, created_at);

CREATE TABLE delivery_operation_day (
    service_date DATE PRIMARY KEY,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'DISPATCHING', 'DISPATCHED', 'CLOSED')),
    admissions_closed_at TIMESTAMP WITH TIME ZONE,
    dispatch_plan_id UUID UNIQUE REFERENCES dispatch_plan(id),
    closure_revision INTEGER NOT NULL DEFAULT 0 CHECK (closure_revision >= 0),
    closed_at TIMESTAMP WITH TIME ZONE,
    closed_by_account_id UUID REFERENCES app_account(id),
    reopened_at TIMESTAMP WITH TIME ZONE,
    reopened_by_account_id UUID REFERENCES app_account(id),
    reopen_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT delivery_operation_day_closure_check CHECK (
        (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by_account_id IS NOT NULL)
        OR status <> 'CLOSED'
    )
);

CREATE TABLE delivery_operation_day_event (
    id UUID PRIMARY KEY,
    service_date DATE NOT NULL REFERENCES delivery_operation_day(service_date) ON DELETE CASCADE,
    event_type VARCHAR(80) NOT NULL,
    actor_account_id UUID REFERENCES app_account(id),
    payload_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX delivery_operation_day_event_idx
    ON delivery_operation_day_event(service_date, created_at);

CREATE TABLE delivery_operation_client_snapshot (
    service_date DATE NOT NULL REFERENCES delivery_operation_day(service_date) ON DELETE CASCADE,
    closure_revision INTEGER NOT NULL CHECK (closure_revision > 0),
    account_id UUID NOT NULL REFERENCES app_account(id),
    display_name VARCHAR(150) NOT NULL,
    phone VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (service_date, closure_revision, account_id)
);

INSERT INTO delivery_operation_day (
    service_date, status, admissions_closed_at, dispatch_plan_id,
    created_at, updated_at
)
SELECT canonical.service_date,
       'DISPATCHED',
       canonical.created_at,
       canonical.id,
       canonical.created_at,
       COALESCE(canonical.completed_processing_at, canonical.created_at)
FROM (
    SELECT dp.*,
           ROW_NUMBER() OVER (
               PARTITION BY dp.service_date
               ORDER BY dp.created_at DESC, dp.id
           ) AS row_number_for_day
    FROM dispatch_plan dp
    WHERE dp.status IN ('READY', 'READY_WITH_WARNINGS')
) canonical
WHERE canonical.row_number_for_day = 1;

-- The legacy back-office allowed several partial READY plans on one date. Keep
-- every parcel by attaching it to its latest successful legacy plan instead of
-- silently retaining only the canonical (latest) plan of the day.
UPDATE delivery_package package
SET dispatch_plan_id = legacy.dispatch_plan_id,
    status = 'DISPATCHED',
    unassigned_reason = CASE
        WHEN legacy.outcome = 'UNASSIGNED'
        THEN COALESCE(legacy.unassigned_message, legacy.unassigned_reason,
                      'Aucune affectation réalisable dans le plan historique.')
        ELSE NULL
    END
FROM (
    SELECT ranked.delivery_package_id, ranked.dispatch_plan_id,
           ranked.outcome, ranked.unassigned_reason, ranked.unassigned_message
    FROM (
        SELECT outcome.delivery_package_id, outcome.dispatch_plan_id,
               outcome.outcome, outcome.unassigned_reason, outcome.unassigned_message,
               ROW_NUMBER() OVER (
                   PARTITION BY outcome.delivery_package_id
                   ORDER BY plan.created_at DESC, plan.id
               ) AS package_plan_rank
        FROM dispatch_package_outcome outcome
        JOIN dispatch_plan plan ON plan.id = outcome.dispatch_plan_id
        WHERE plan.status IN ('READY', 'READY_WITH_WARNINGS')
    ) ranked
    WHERE ranked.package_plan_rank = 1
) legacy
WHERE package.id = legacy.delivery_package_id;

UPDATE delivery_request request
SET status = CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM delivery_package package
            WHERE package.delivery_request_id = request.id
              AND package.status <> 'DISPATCHED'
        ) THEN 'DISPATCHED'
        WHEN EXISTS (
            SELECT 1 FROM delivery_package package
            WHERE package.delivery_request_id = request.id
              AND package.status = 'DISPATCHED'
        ) THEN 'PARTIALLY_DISPATCHED'
        ELSE 'PENDING'
    END,
    updated_at = CURRENT_TIMESTAMP;
