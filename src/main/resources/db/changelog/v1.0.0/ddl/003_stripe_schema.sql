-- Create billing_profiles table
CREATE TABLE payments.billing_profiles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE,
    stripe_customer_id VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Add Stripe fields to subscriptions table
ALTER TABLE payments.subscriptions ADD COLUMN stripe_subscription_id VARCHAR(100);

-- Add Stripe fields to subscription_plan_details table
ALTER TABLE payments.subscription_plan_details ADD COLUMN stripe_product_id VARCHAR(100);
ALTER TABLE payments.subscription_plan_details ADD COLUMN stripe_price_id VARCHAR(100);
