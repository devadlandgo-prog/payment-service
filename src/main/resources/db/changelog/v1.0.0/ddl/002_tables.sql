SET search_path TO payments;

CREATE TABLE IF NOT EXISTS subscriptions (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  plan VARCHAR(50) NOT NULL,
  status VARCHAR(50) NOT NULL,
  amount DECIMAL(15,2) NOT NULL,
  start_date TIMESTAMP NOT NULL,
  end_date TIMESTAMP NOT NULL,
  payment_method VARCHAR(100),
  auto_renew BOOLEAN DEFAULT TRUE,
  cancelled_at TIMESTAMP,
  cancellation_reason VARCHAR(500),
  max_vendor_views_per_month INTEGER,
  max_saved_lands INTEGER,
  can_access_premium_listings BOOLEAN,
  can_contact_vendor_directly BOOLEAN,
  payment_reference VARCHAR(255),
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS payments (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  subscription_id UUID REFERENCES subscriptions(id),
  amount DECIMAL(10,2) NOT NULL,
  currency VARCHAR(3) NOT NULL DEFAULT 'CAD',
  status VARCHAR(20) NOT NULL,
  description VARCHAR(255),
  provider VARCHAR(50),
  provider_transaction_id VARCHAR(255),
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS subscription_plan_details (
  id UUID PRIMARY KEY,
  plan_type VARCHAR(50) NOT NULL,
  plan_category VARCHAR(50) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  monthly_price DECIMAL(15,2) NOT NULL,
  annual_price DECIMAL(15,2) NOT NULL,
  currency VARCHAR(10) NOT NULL,
  max_vendor_views INTEGER,
  max_saved_lands INTEGER,
  can_access_premium BOOLEAN,
  can_contact_vendor BOOLEAN,
  is_popular BOOLEAN,
  is_active BOOLEAN DEFAULT TRUE,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS plan_features (
  plan_id UUID NOT NULL REFERENCES subscription_plan_details(id) ON DELETE CASCADE,
  feature VARCHAR(255)
);

-- Unique on plan_type, added separately rather than inline on the CREATE TABLE
-- above, because that statement is IF NOT EXISTS and therefore a no-op against
-- a database where the table already exists - which is every database that has
-- run this changelog before today.
--
-- Two things depend on this constraint existing:
--   * 001_seed_reference_data.sql inserts with ON CONFLICT (plan_type), which
--     Postgres rejects outright when no matching unique constraint exists:
--     "there is no unique or exclusion constraint matching the ON CONFLICT
--     specification".
--   * v1.0.1/ddl/004 drops a constraint by the exact name below before adding
--     the composite (plan_type, plan_category) one, so the name matters.
--
-- It was missing because the table was originally created by the legacy
-- sql/004_3_create_plan_details_table.sql, which did declare plan_type UNIQUE.
-- When the changelog was restructured into v1.0.0 the constraint was dropped
-- from the definition, and nothing failed: existing databases already had it
-- from the legacy script. The gap only appears on a database built from this
-- changelog alone, which is exactly what a rebuild in a new AWS account does.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE c.conname = 'subscription_plan_details_plan_type_key'
      AND t.relname = 'subscription_plan_details'
      AND n.nspname = 'payments'
  ) THEN
    ALTER TABLE subscription_plan_details
      ADD CONSTRAINT subscription_plan_details_plan_type_key UNIQUE (plan_type);
  END IF;
END
$$;
