SET search_path TO payments;

-- The expertise cap for a market professional lived only in the mobile client, which
-- inferred it from the plan name: Basic/Single 2, Featured/Premium/Professional 5,
-- Enterprise unlimited. An admin changing a plan therefore could not change what a
-- professional was allowed to select, and nothing on the server enforced the cap at all.
--
-- -1 is unlimited, matching max_vendor_views and max_saved_lands on the Enterprise seed
-- row. 0 allows no selection, and is the column default deliberately: a professional plan
-- whose cap nobody has configured must fail closed rather than read as unlimited.
ALTER TABLE subscription_plan_details
    ADD COLUMN IF NOT EXISTS max_expertise_selections INTEGER NOT NULL DEFAULT 0;

-- Backfilled to exactly what the client does today, so exposing the field changes no
-- user's allowance on deploy and only an explicit admin edit moves a cap. The ELSE
-- matches the client's own fallback for a tier it does not recognise.
UPDATE subscription_plan_details
SET max_expertise_selections = CASE
        WHEN LOWER(plan_type) IN ('enterprise', 'unlimited') THEN -1
        WHEN LOWER(plan_type) IN ('featured', 'premium', 'professional') THEN 5
        WHEN LOWER(plan_type) IN ('basic', 'single') THEN 2
        WHEN LOWER(plan_type) = 'free' THEN 0
        ELSE 2
    END,
    updated_at = now()
WHERE LOWER(COALESCE(plan_category, '')) = 'market_profession';

-- Per-subscription allowance. NULL means follow the plan's configured cap, which is what
-- every existing row gets: an admin raising Basic's cap then reaches current subscribers
-- immediately. A value here is a grandfathered or account-specific allowance and wins over
-- the catalogue.
ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS max_expertise_selections INTEGER;
