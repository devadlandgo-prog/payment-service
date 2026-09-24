SET search_path TO payments;

-- Carry forward what users already bought. Before the ledger existed, a land
-- purchase was a row in `subscriptions` with plan_category = 'land_listing' and
-- the plan's credit count in max_vendor_views; anything already ACTIVE was paid
-- for, so it becomes a PURCHASE entry rather than silently vanishing.
INSERT INTO listing_credit_ledger (
    id, user_id, entry_type, credits, idempotency_key,
    plan_id, plan_name, plan_type, amount, currency,
    reason, created_at, updated_at, deleted
)
SELECT
    gen_random_uuid(),
    s.user_id,
    'PURCHASE',
    COALESCE(d.listing_credits, s.max_vendor_views_per_month, 1),
    'migration.subscription:' || s.id,
    d.id,
    d.name,
    s.plan,
    s.amount,
    COALESCE(d.currency, 'CAD'),
    'Migrated from pre-ledger land listing subscription',
    COALESCE(s.created_at, NOW()),
    NOW(),
    FALSE
FROM subscriptions s
LEFT JOIN subscription_plan_details d
       ON LOWER(d.plan_type) = LOWER(s.plan)
      AND LOWER(d.plan_category) = 'land_listing'
WHERE LOWER(COALESCE(s.plan_category, '')) = 'land_listing'
  AND s.status = 'ACTIVE'
  AND s.deleted = FALSE
  AND NOT EXISTS (
      SELECT 1 FROM listing_credit_ledger l
       WHERE l.idempotency_key = 'migration.subscription:' || s.id
  );

INSERT INTO listing_credit_balances (user_id, credits_purchased, credits_used, updated_at)
SELECT user_id, SUM(credits), 0, NOW()
  FROM listing_credit_ledger
 WHERE entry_type = 'PURCHASE'
 GROUP BY user_id
ON CONFLICT (user_id) DO NOTHING;
