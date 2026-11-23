-- Existing preparations keep their original split-payment workflow and history.
ALTER TABLE cash_briefing_batch ADD COLUMN workflow_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE cash_briefing_batch ADD CONSTRAINT cash_briefing_workflow_version_ck
    CHECK (workflow_version IN (1, 2));

CREATE TABLE cash_briefing_courier_transfer (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES cash_briefing_batch(id),
    from_courier_account_id UUID NOT NULL REFERENCES courier_profile(id),
    from_courier_name VARCHAR(200) NOT NULL,
    to_courier_account_id UUID NOT NULL REFERENCES courier_profile(id),
    to_courier_name VARCHAR(200) NOT NULL,
    amount_ariary BIGINT NOT NULL CHECK (amount_ariary > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'REPLACED')),
    movement_id UUID REFERENCES cash_ledger_movement(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (from_courier_account_id <> to_courier_account_id),
    UNIQUE (batch_id, from_courier_account_id, to_courier_account_id)
);
CREATE INDEX cash_briefing_courier_transfer_batch_idx ON cash_briefing_courier_transfer(batch_id, status);
CREATE INDEX cash_ledger_reservation_proposal_idx ON cash_ledger_reservation(proposal_id, status);
