-- Denormalized destinations are intentionally not foreign keys: profile updates
-- may replace their rows, while financial history must remain unchanged.
-- Unknown historical holders stay NULL rather than inventing an account name.
ALTER TABLE cash_briefing_transfer ADD COLUMN seller_payout_destination_id UUID;
ALTER TABLE cash_briefing_transfer ADD COLUMN seller_payout_account_holder VARCHAR(200);
ALTER TABLE cash_briefing_transfer ADD COLUMN seller_payout_operator VARCHAR(80);
