SET search_path TO payments;

-- plan_category is compared case-insensitively everywhere in the service, but the
-- unique index on (plan_type, plan_category) is not: 'LAND_LISTING' and 'land_listing'
-- are two different slots. The v1.0.4 seed inserts uppercase, and v1.0.1 had already
-- normalised everything to lowercase, so SINGLE ended up in a slot of its own while
-- PROFESSIONAL and UNLIMITED sat in the lowercase one. The index was therefore no
-- longer preventing two rows for the same tier.
--
-- Lowercase is the canonical form: SubscriptionService lowercases every incoming
-- category before saving, so any other spelling can only have come from a seed.
UPDATE subscription_plan_details
SET plan_category = LOWER(plan_category),
    updated_at = now()
WHERE plan_category IS NOT NULL
  AND plan_category <> LOWER(plan_category);

-- Make the casing irrelevant from here on, so a future seed cannot reopen the split.
-- Added alongside subscription_plan_details_plan_type_category_key rather than
-- replacing it: the entity declares that constraint by name, and this index is
-- strictly stricter, so keeping both costs one index and breaks nothing.
CREATE UNIQUE INDEX IF NOT EXISTS subscription_plan_details_tier_category_ci_key
    ON subscription_plan_details (LOWER(plan_type), LOWER(plan_category));
