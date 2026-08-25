CREATE TABLE client_contact_number (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES client_profile(account_id) ON DELETE CASCADE,
    label VARCHAR(100),
    number VARCHAR(50) NOT NULL,
    priority INTEGER NOT NULL CHECK (priority > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT client_contact_number_priority_uk UNIQUE (account_id, priority)
);

CREATE INDEX client_contact_number_account_idx
    ON client_contact_number(account_id, priority);

-- Keep the historical profile phone as the first contact.  The account UUID is
-- safe to reuse as the identifier because this is a separate table.
INSERT INTO client_contact_number (id, account_id, label, number, priority)
SELECT account.id, account.id, 'Principal', account.phone, 1
FROM app_account account
JOIN client_profile profile ON profile.account_id = account.id
WHERE account.phone IS NOT NULL AND TRIM(account.phone) <> '';

CREATE TABLE client_payout_destination (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES client_profile(account_id) ON DELETE CASCADE,
    operator VARCHAR(80) NOT NULL,
    account_holder VARCHAR(150) NOT NULL,
    number VARCHAR(100) NOT NULL,
    priority INTEGER NOT NULL CHECK (priority > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT client_payout_destination_priority_uk UNIQUE (account_id, priority)
);

CREATE INDEX client_payout_destination_account_idx
    ON client_payout_destination(account_id, priority);

ALTER TABLE courier_profile ADD COLUMN phone_number VARCHAR(50);
ALTER TABLE courier_profile ADD COLUMN payout_operator VARCHAR(80);
ALTER TABLE courier_profile ADD COLUMN payout_account_holder VARCHAR(150);
ALTER TABLE courier_profile ADD COLUMN payout_number VARCHAR(100);

UPDATE courier_profile SET phone_number = contact
WHERE contact IS NOT NULL AND TRIM(contact) <> '';

ALTER TABLE courier_profile
    ADD CONSTRAINT courier_profile_payout_consistency_check CHECK (
        (payout_operator IS NULL AND payout_account_holder IS NULL AND payout_number IS NULL)
        OR
        (payout_operator IS NOT NULL AND payout_account_holder IS NOT NULL AND payout_number IS NOT NULL)
    );

-- delivery_fee_ariary remains the immutable price calculated when the parcel
-- was registered.  Only final_delivery_fee_ariary participates in settlement.
ALTER TABLE delivery_package ADD COLUMN final_delivery_fee_ariary BIGINT;
ALTER TABLE delivery_package ADD COLUMN delivery_fee_override_reason VARCHAR(1000);
ALTER TABLE delivery_package ADD COLUMN delivery_fee_overridden_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery_package
    ADD COLUMN delivery_fee_overridden_by_account_id UUID REFERENCES app_account(id);
ALTER TABLE delivery_package ADD COLUMN delivery_fee_version BIGINT NOT NULL DEFAULT 0;

UPDATE delivery_package
SET final_delivery_fee_ariary = delivery_fee_ariary;

ALTER TABLE delivery_package
    ADD CONSTRAINT delivery_package_final_fee_check CHECK (
        final_delivery_fee_ariary IS NULL OR final_delivery_fee_ariary >= 0
    );
ALTER TABLE delivery_package
    ADD CONSTRAINT delivery_package_fee_version_check CHECK (delivery_fee_version >= 0);
ALTER TABLE delivery_package
    ADD CONSTRAINT delivery_package_fee_override_consistency_check CHECK (
        (delivery_fee_ariary IS NULL
            AND (
                (final_delivery_fee_ariary IS NULL
                    AND delivery_fee_override_reason IS NULL
                    AND delivery_fee_overridden_at IS NULL
                    AND delivery_fee_overridden_by_account_id IS NULL)
                OR
                (final_delivery_fee_ariary IS NOT NULL
                    AND delivery_fee_override_reason IS NOT NULL
                    AND delivery_fee_overridden_at IS NOT NULL
                    AND delivery_fee_overridden_by_account_id IS NOT NULL)
            ))
        OR
        (delivery_fee_ariary IS NOT NULL
            AND (
                (final_delivery_fee_ariary IS NULL
                    AND delivery_fee_override_reason IS NULL
                    AND delivery_fee_overridden_at IS NULL
                    AND delivery_fee_overridden_by_account_id IS NULL)
                OR
                (final_delivery_fee_ariary = delivery_fee_ariary
                    AND delivery_fee_override_reason IS NULL
                    AND delivery_fee_overridden_at IS NULL
                    AND delivery_fee_overridden_by_account_id IS NULL)
                OR
                (delivery_fee_override_reason IS NOT NULL
                    AND delivery_fee_overridden_at IS NOT NULL
                    AND delivery_fee_overridden_by_account_id IS NOT NULL)
            ))
    );

CREATE TABLE delivery_fee_change_event (
    id UUID PRIMARY KEY,
    delivery_package_id UUID NOT NULL REFERENCES delivery_package(id) ON DELETE CASCADE,
    fee_version BIGINT NOT NULL CHECK (fee_version > 0),
    change_mode VARCHAR(20) NOT NULL CHECK (change_mode IN ('CALCULATED', 'MANUAL')),
    calculated_fee_ariary BIGINT CHECK (calculated_fee_ariary IS NULL OR calculated_fee_ariary >= 0),
    previous_final_fee_ariary BIGINT CHECK (previous_final_fee_ariary IS NULL OR previous_final_fee_ariary >= 0),
    new_final_fee_ariary BIGINT CHECK (new_final_fee_ariary IS NULL OR new_final_fee_ariary >= 0),
    reason VARCHAR(1000),
    actor_account_id UUID NOT NULL REFERENCES app_account(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT delivery_fee_change_event_package_version_uk
        UNIQUE (delivery_package_id, fee_version),
    CONSTRAINT delivery_fee_change_event_reason_check CHECK (
        (change_mode = 'MANUAL' AND reason IS NOT NULL)
        OR change_mode = 'CALCULATED'
    )
);

CREATE INDEX delivery_fee_change_event_package_idx
    ON delivery_fee_change_event(delivery_package_id, created_at);
