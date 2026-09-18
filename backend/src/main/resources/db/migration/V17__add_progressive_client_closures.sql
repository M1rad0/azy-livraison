CREATE TABLE delivery_operation_client_closure (
    service_date DATE NOT NULL REFERENCES delivery_operation_day(service_date) ON DELETE CASCADE,
    client_account_id UUID NOT NULL REFERENCES app_account(id),
    status VARCHAR(20) NOT NULL DEFAULT 'CLOSED'
        CHECK (status IN ('CLOSED', 'REOPENED')),
    closure_revision INTEGER NOT NULL CHECK (closure_revision > 0),
    report_id UUID NOT NULL UNIQUE,
    package_count INTEGER NOT NULL DEFAULT 0 CHECK (package_count >= 0),
    recovered_ariary BIGINT NOT NULL DEFAULT 0,
    delivery_fees_ariary BIGINT NOT NULL DEFAULT 0 CHECK (delivery_fees_ariary >= 0),
    amount_to_remit_ariary BIGINT NOT NULL DEFAULT 0,
    closed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_by_account_id UUID NOT NULL REFERENCES app_account(id),
    reopened_at TIMESTAMP WITH TIME ZONE,
    reopened_by_account_id UUID REFERENCES app_account(id),
    reopen_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (service_date, client_account_id, closure_revision),
    CONSTRAINT delivery_operation_client_closure_reopen_check CHECK (
        (status = 'CLOSED' AND reopened_at IS NULL
            AND reopened_by_account_id IS NULL AND reopen_reason IS NULL)
        OR (status = 'REOPENED' AND reopened_at IS NOT NULL
            AND reopened_by_account_id IS NOT NULL AND reopen_reason IS NOT NULL)
    )
);

CREATE INDEX delivery_operation_client_closure_client_date_idx
    ON delivery_operation_client_closure(client_account_id, service_date DESC);

-- Historical CLOSED days deliberately remain day-wide closures. Their client
-- settlements can share the same closure revision and their courier batch is
-- likewise day-wide (client_account_id is NULL). Synthesizing progressive
-- closures here would make that single legacy batch inaccessible and would
-- invite duplicate client compensation. The application keeps its explicit
-- legacy fallback for those days; only closures created after this migration
-- use delivery_operation_client_closure.

ALTER TABLE courier_compensation_batch
    ADD COLUMN client_account_id UUID REFERENCES app_account(id);

ALTER TABLE courier_compensation_batch
    ADD CONSTRAINT courier_compensation_batch_client_closure_fk
        FOREIGN KEY (service_date, client_account_id, closure_revision)
        REFERENCES delivery_operation_client_closure (
            service_date, client_account_id, closure_revision
        );

CREATE INDEX courier_compensation_batch_client_date_idx
    ON courier_compensation_batch(client_account_id, service_date DESC);
