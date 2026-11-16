-- A separate accounting epoch: no historic settlement or collection is imported.
CREATE TABLE cash_ledger_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
INSERT INTO cash_ledger_state (id) VALUES (1);

CREATE TABLE cash_ledger_account (
    account_key VARCHAR(120) PRIMARY KEY,
    account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('AZY', 'COURIER', 'SELLER')),
    entity_id UUID,
    CHECK ((account_type = 'AZY' AND entity_id IS NULL AND account_key = 'AZY')
        OR (account_type <> 'AZY' AND entity_id IS NOT NULL))
);
INSERT INTO cash_ledger_account (account_key, account_type) VALUES ('AZY', 'AZY');
INSERT INTO cash_ledger_account (account_key, account_type, entity_id)
SELECT 'COURIER:' || CAST(id AS VARCHAR), 'COURIER', id FROM courier_profile;
INSERT INTO cash_ledger_account (account_key, account_type, entity_id)
SELECT 'SELLER:' || CAST(id AS VARCHAR), 'SELLER', id FROM app_account WHERE role = 'CLIENT';

CREATE TABLE cash_ledger_movement (
    id UUID PRIMARY KEY,
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('TRANSFER', 'ADJUSTMENT', 'COLLECTION', 'SELLER_OBLIGATION', 'REVERSAL')),
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    request_fingerprint VARCHAR(64) NOT NULL,
    from_key VARCHAR(120) REFERENCES cash_ledger_account(account_key),
    to_key VARCHAR(120) REFERENCES cash_ledger_account(account_key),
    amount_ariary BIGINT NOT NULL CHECK (amount_ariary > 0),
    delta_ariary BIGINT,
    payment_method VARCHAR(80),
    transaction_reference VARCHAR(200),
    note VARCHAR(1000),
    source_reference UUID,
    reversal_of UUID UNIQUE REFERENCES cash_ledger_movement(id),
    actor_account_id UUID NOT NULL REFERENCES app_account(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    proof_storage_key VARCHAR(200),
    proof_original_filename VARCHAR(255),
    proof_content_type VARCHAR(100),
    proof_size_bytes BIGINT,
    proof_sha256 CHAR(64),
    CHECK (from_key IS NOT NULL OR to_key IS NOT NULL),
    CHECK (from_key IS NULL OR to_key IS NULL OR from_key <> to_key),
    CHECK ((kind = 'REVERSAL' AND reversal_of IS NOT NULL) OR (kind <> 'REVERSAL' AND reversal_of IS NULL)),
    CHECK ((proof_storage_key IS NULL AND proof_original_filename IS NULL AND proof_content_type IS NULL
            AND proof_size_bytes IS NULL AND proof_sha256 IS NULL)
        OR (proof_storage_key IS NOT NULL AND proof_original_filename IS NOT NULL AND proof_content_type IS NOT NULL
            AND proof_size_bytes > 0 AND proof_sha256 IS NOT NULL))
);
CREATE INDEX cash_ledger_movement_created_idx ON cash_ledger_movement(created_at DESC, id);
CREATE TABLE cash_ledger_leg (
    movement_id UUID NOT NULL REFERENCES cash_ledger_movement(id),
    account_key VARCHAR(120) NOT NULL REFERENCES cash_ledger_account(account_key),
    delta_ariary BIGINT NOT NULL CHECK (delta_ariary <> 0),
    PRIMARY KEY (movement_id, account_key)
);
CREATE INDEX cash_ledger_leg_account_idx ON cash_ledger_leg(account_key);

CREATE TABLE cash_ledger_collection_state (
    package_id UUID PRIMARY KEY REFERENCES delivery_package(id),
    courier_id UUID REFERENCES courier_profile(id),
    observed_amount_ariary BIGINT NOT NULL DEFAULT 0 CHECK (observed_amount_ariary >= 0),
    baseline_courier_id UUID REFERENCES courier_profile(id),
    baseline_amount_ariary BIGINT NOT NULL DEFAULT 0 CHECK (baseline_amount_ariary >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Only an existing, actually recorded collection is an opening baseline. Pending
-- packages remain zero and their future actual receipts enter the new ledger.
INSERT INTO cash_ledger_collection_state (
    package_id, courier_id, observed_amount_ariary, baseline_courier_id, baseline_amount_ariary)
SELECT id, actual_delivery_courier_id, COALESCE(actual_collected_ariary, 0),
       actual_delivery_courier_id, COALESCE(actual_collected_ariary, 0)
FROM delivery_package;

CREATE TABLE cash_ledger_obligation (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL,
    seller_account_id UUID NOT NULL REFERENCES app_account(id),
    service_date DATE NOT NULL,
    baseline_amount_ariary BIGINT NOT NULL DEFAULT 0,
    observed_amount_ariary BIGINT NOT NULL DEFAULT 0,
    recognized_amount_ariary BIGINT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (report_id, seller_account_id)
);
INSERT INTO cash_ledger_obligation (
    id, report_id, seller_account_id, service_date, baseline_amount_ariary, observed_amount_ariary)
SELECT id, report_id, client_account_id, service_date, amount_due_ariary, amount_due_ariary
FROM client_daily_settlement;
CREATE INDEX cash_ledger_obligation_seller_date_idx ON cash_ledger_obligation(seller_account_id, service_date, id);
CREATE TABLE cash_ledger_allocation (
    movement_id UUID NOT NULL REFERENCES cash_ledger_movement(id),
    obligation_id UUID NOT NULL REFERENCES cash_ledger_obligation(id),
    signed_amount_ariary BIGINT NOT NULL CHECK (signed_amount_ariary <> 0),
    PRIMARY KEY (movement_id, obligation_id)
);

-- Briefing proposals reserve money but never create ledger legs. Confirming a
-- transfer remains a separate administrative action.
CREATE TABLE cash_ledger_reservation (
    id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL,
    account_key VARCHAR(120) NOT NULL REFERENCES cash_ledger_account(account_key),
    amount_ariary BIGINT NOT NULL CHECK (amount_ariary > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED', 'CONFIRMED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX cash_ledger_reservation_account_idx ON cash_ledger_reservation(account_key, status);
