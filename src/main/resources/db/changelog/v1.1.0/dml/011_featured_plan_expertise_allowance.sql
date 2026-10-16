SET search_path TO payments;

-- The v1.0.9 backfill derived each allowance from plan_type, and read this row as an
-- Enterprise tier: unlimited. It is not sold as one. The row is the ENTERPRISE plan seeded
-- in v1.0.0 (a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14), later renamed to "Featured" and priced
-- at $99.99 while plan_type stayed ENTERPRISE, so the catalogue offers exactly two
-- professional plans - Basic and Featured - and no Enterprise at all.
--
-- The mobile client that owned this cap until now puts Featured at 5, and its tier
-- inference could land on either answer for this row depending on which of plan, planName
-- or planTier it read first, since the same plan presents as both ENTERPRISE and Featured.
-- 5 is the intended entitlement.
--
-- Matched on the mismatch itself rather than on the id, so an environment holding a
-- genuine Enterprise tier named "Enterprise" keeps its unlimited allowance.
UPDATE subscription_plan_details
SET max_expertise_selections = 5,
    updated_at = now()
WHERE LOWER(COALESCE(plan_category, '')) = 'market_profession'
  AND LOWER(plan_type) = 'enterprise'
  AND LOWER(COALESCE(name, '')) LIKE '%featured%';
