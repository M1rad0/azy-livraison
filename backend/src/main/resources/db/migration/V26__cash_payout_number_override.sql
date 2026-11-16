-- Keep profile-backed proposals reactive to profile changes, while preserving an
-- administrator's explicit destination for this payout only. Old rows retain
-- their existing profile-backed behavior and confirmed snapshots stay untouched.
ALTER TABLE cash_briefing_transfer
    ADD COLUMN seller_payout_number_override BOOLEAN NOT NULL DEFAULT FALSE;
