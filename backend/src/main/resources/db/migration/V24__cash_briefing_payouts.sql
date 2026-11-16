CREATE TABLE cash_briefing_batch (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES dispatch_plan(id),
    service_date DATE NOT NULL,
    ledger_revision BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'COMPLETED', 'PLAN_INVALID')),
    actor_account_id UUID NOT NULL REFERENCES app_account(id),
    idempotency_key VARCHAR(100) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT cash_briefing_actor_key_uk UNIQUE (actor_account_id, idempotency_key)
);
CREATE INDEX cash_briefing_active_idx ON cash_briefing_batch(status, plan_id);

CREATE TABLE cash_briefing_transfer (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES cash_briefing_batch(id),
    seller_account_id UUID NOT NULL REFERENCES app_account(id),
    seller_name VARCHAR(200) NOT NULL,
    seller_payout_number VARCHAR(100),
    courier_account_id UUID NOT NULL REFERENCES courier_profile(id),
    courier_name VARCHAR(200) NOT NULL,
    amount_ariary BIGINT NOT NULL CHECK (amount_ariary > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'REPLACED')),
    movement_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX cash_briefing_transfer_batch_idx ON cash_briefing_transfer(batch_id, status);
CREATE INDEX cash_briefing_transfer_seller_idx ON cash_briefing_transfer(seller_account_id, status);
-- The shared cash_ledger_state writer lock serializes preparation and guarantees
-- a single active proposal per plan without database-specific filtered indexes.
