SET search_path TO payments;

-- v1.0.5 also converted existing active land subscriptions into PURCHASE ledger
-- rows, using the same wrong credit count for the legacy tiers. A user who had
-- been on the FREE land plan was migrated with 5 credits instead of the 1 the
-- old code gave them.
--
-- Corrections are appended, never edited -- that is the point of the ledger --
-- so each over-granted row gets a matching REVERSAL and the balance is reduced
-- by the same amount. Idempotent through the unique idempotency_key: re-running
-- this changeset inserts nothing.
INSERT INTO listing_credit_ledger (
    id, user_id, entry_type, credits, idempotency_key,
    plan_id, plan_name, plan_type, reason,
    created_at, updated_at, deleted
)
SELECT
    gen_random_uuid(),
    l.user_id,
    'REVERSAL',
    -(l.credits - correct.credits),
    'migration.correct:' || l.id,
    l.plan_id,
    l.plan_name,
    l.plan_type,
    'Correcting v1.0.5 migration: max_vendor_views on this legacy tier means '
        || 'vendor views per month, not listing credits',
    NOW(), NOW(), FALSE
FROM listing_credit_ledger l
JOIN (VALUES ('FREE', 1), ('BASIC', 2), ('PREMIUM', 3)) AS correct(plan_type, credits)
       ON UPPER(l.plan_type) = correct.plan_type
WHERE l.entry_type = 'PURCHASE'
  AND l.idempotency_key LIKE 'migration.subscription:%'
  AND l.credits > correct.credits
  AND NOT EXISTS (
      SELECT 1 FROM listing_credit_ledger x
       WHERE x.idempotency_key = 'migration.correct:' || l.id
  );

-- Apply those reversals to the running totals. Never drops the purchased total
-- below what has already been spent: a credit already backing a live listing
-- cannot be taken back by arithmetic.
UPDATE listing_credit_balances b
   SET credits_purchased = GREATEST(b.credits_used, b.credits_purchased - r.total_reversed),
       updated_at = NOW()
  FROM (
    SELECT user_id, SUM(-credits) AS total_reversed
      FROM listing_credit_ledger
     WHERE entry_type = 'REVERSAL'
       AND idempotency_key LIKE 'migration.correct:%'
     GROUP BY user_id
  ) r
 WHERE b.user_id = r.user_id;
