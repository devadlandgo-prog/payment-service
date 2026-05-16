SET search_path TO payments;

-- Normalize existing plan category values
UPDATE subscription_plan_details
SET plan_category = LOWER(plan_category)
WHERE plan_category IS NOT NULL;

-- Default missing categories to land_listing so the uniqueness constraint can be applied.
-- Administrators can correct any legacy rows manually if needed.
UPDATE subscription_plan_details
SET plan_category = 'land_listing'
WHERE plan_category IS NULL;

-- Drop legacy unique constraint on plan_type only
ALTER TABLE subscription_plan_details
    DROP CONSTRAINT IF EXISTS subscription_plan_details_plan_type_key;

-- Enforce composite uniqueness and non-null category going forward
ALTER TABLE subscription_plan_details
    ALTER COLUMN plan_category SET NOT NULL;

ALTER TABLE subscription_plan_details
    ADD CONSTRAINT subscription_plan_details_plan_type_category_key
        UNIQUE (plan_type, plan_category);
