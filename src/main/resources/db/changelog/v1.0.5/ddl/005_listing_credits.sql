SET search_path TO payments;

-- Plans must say what they are. Land listing packages are bought outright and
-- grant a credit count; market professional plans recur. Overloading price
-- columns and max_duration to imply this was the source of land purchases being
-- treated as replaceable, expiring subscriptions.
ALTER TABLE subscription_plan_details
    ADD COLUMN IF NOT EXISTS billing_model VARCHAR(20) NOT NULL DEFAULT 'RECURRING';

ALTER TABLE subscription_plan_details
    ADD COLUMN IF NOT EXISTS listing_credits INTEGER;

-- Existing land packages stored their credit count in max_vendor_views, which
-- means something else entirely for market plans. Backfill the explicit column
-- rather than leaving two readings of the same number.
UPDATE subscription_plan_details
   SET billing_model = 'ONE_TIME',
       listing_credits = COALESCE(listing_credits, max_vendor_views, 0)
 WHERE LOWER(plan_category) = 'land_listing';

-- Append-only record of every credit movement: purchases, use, admin
-- corrections and refund reversals. Corrections are new rows, never edits.
CREATE TABLE IF NOT EXISTS listing_credit_ledger (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    entry_type VARCHAR(30) NOT NULL,
    credits INTEGER NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    plan_id UUID,
    plan_name VARCHAR(200),
    plan_type VARCHAR(50),
    amount NUMERIC(15, 2),
    currency VARCHAR(10),
    payment_reference VARCHAR(200),
    payment_id UUID,
    listing_id UUID,
    actor_id UUID,
    reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT listing_credit_ledger_idempotency_key_key UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_listing_credit_ledger_user
    ON listing_credit_ledger (user_id, created_at DESC);

-- Running totals. Spending a credit is one conditional UPDATE against this row,
-- which is what stops two concurrent listing submissions taking the same last
-- credit. There is no end date here: credits never expire.
CREATE TABLE IF NOT EXISTS listing_credit_balances (
    user_id UUID PRIMARY KEY,
    credits_purchased INTEGER NOT NULL DEFAULT 0,
    credits_used INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT listing_credit_balances_non_negative CHECK (credits_purchased >= 0 AND credits_used >= 0)
);
