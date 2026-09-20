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
-- a database where the table already exists.
--
-- Two things depend on this constraint:
--   * 001_seed_reference_data.sql inserts with ON CONFLICT (plan_type), which
--     Postgres rejects when no matching unique constraint exists.
--   * v1.0.1/ddl/004 drops a constraint by the exact name below before adding
--     the composite (plan_type, plan_category) one, so the name matters.
--
-- It went missing when the changelog was restructured into v1.0.0: the legacy
-- sql/004_3_create_plan_details_table.sql did declare plan_type UNIQUE, so
-- every existing database already had it and CREATE TABLE IF NOT EXISTS hid
-- the gap. It only shows up on a database built from this changelog alone.
--
-- DROP IF EXISTS then ADD, rather than a dollar-quoted DO block: Liquibase splits
-- sqlFile contents on semicolons, so a dollar-quoted body is cut in half and
-- fails with "Unterminated dollar quote". This form is idempotent and needs no
-- quoting. Dropping first is safe because the constraint, where it exists, has
-- always enforced uniqueness - there can be no duplicates to violate the
-- re-added constraint.
ALTER TABLE subscription_plan_details
    DROP CONSTRAINT IF EXISTS subscription_plan_details_plan_type_key;

ALTER TABLE subscription_plan_details
    ADD CONSTRAINT subscription_plan_details_plan_type_key UNIQUE (plan_type);
