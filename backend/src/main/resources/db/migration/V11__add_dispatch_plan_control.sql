ALTER TABLE dispatch_plan ADD COLUMN revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE dispatch_plan ADD COLUMN mode VARCHAR(20) NOT NULL DEFAULT 'AUTOMATIC';
ALTER TABLE dispatch_plan ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE dispatch_plan ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dispatch_plan ADD COLUMN supersedes_plan_id UUID REFERENCES dispatch_plan(id);
ALTER TABLE dispatch_plan ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE dispatch_plan ADD COLUMN archived_by_account_id UUID REFERENCES app_account(id);
ALTER TABLE dispatch_plan ADD COLUMN archive_reason VARCHAR(1000);
ALTER TABLE dispatch_plan ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE dispatch_plan
    ADD CONSTRAINT dispatch_plan_revision_check CHECK (revision > 0);
ALTER TABLE dispatch_plan
    ADD CONSTRAINT dispatch_plan_mode_check CHECK (mode IN ('AUTOMATIC', 'MANUAL'));
ALTER TABLE dispatch_plan
    ADD CONSTRAINT dispatch_plan_lifecycle_status_check CHECK (
        lifecycle_status IN ('ACTIVE', 'CANCELLED', 'SUPERSEDED')
    );
ALTER TABLE dispatch_plan
    ADD CONSTRAINT dispatch_plan_archive_check CHECK (
        (lifecycle_status IN ('CANCELLED', 'SUPERSEDED')
            AND archived_at IS NOT NULL
            AND archived_by_account_id IS NOT NULL
            AND archive_reason IS NOT NULL)
        OR lifecycle_status = 'ACTIVE'
    );

CREATE INDEX dispatch_plan_supersedes_idx ON dispatch_plan(supersedes_plan_id);
