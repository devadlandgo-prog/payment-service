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
