ALTER TABLE delivery_pricing_policy
    ADD COLUMN morning_surcharge_ariary BIGINT NOT NULL DEFAULT 500 CHECK (morning_surcharge_ariary >= 0);
ALTER TABLE delivery_pricing_policy
    ADD COLUMN afternoon_surcharge_ariary BIGINT NOT NULL DEFAULT 500 CHECK (afternoon_surcharge_ariary >= 0);

CREATE TABLE delivery_pricing_keyword_rule (
    id UUID PRIMARY KEY,
    pricing_policy_id SMALLINT NOT NULL REFERENCES delivery_pricing_policy(id) ON DELETE CASCADE,
    rule_order INTEGER NOT NULL CHECK (rule_order > 0),
    keyword VARCHAR(200) NOT NULL CHECK (LENGTH(TRIM(keyword)) > 0),
    delivery_fee_ariary BIGINT NOT NULL CHECK (delivery_fee_ariary >= 0),
    CONSTRAINT delivery_pricing_keyword_order_uk UNIQUE (pricing_policy_id, rule_order)
);
