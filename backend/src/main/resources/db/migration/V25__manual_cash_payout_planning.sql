ALTER TABLE cash_briefing_batch ADD COLUMN planning_mode VARCHAR(10) NOT NULL DEFAULT 'AUTO';
ALTER TABLE cash_briefing_batch ADD CONSTRAINT cash_briefing_planning_mode_ck
    CHECK (planning_mode IN ('AUTO', 'MANUAL'));
ALTER TABLE cash_briefing_batch ADD COLUMN request_fingerprint VARCHAR(64);
