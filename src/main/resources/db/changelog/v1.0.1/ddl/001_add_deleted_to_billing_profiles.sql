-- Add deleted column to billing_profiles table to align with BaseEntity
ALTER TABLE payments.billing_profiles ADD COLUMN deleted BOOLEAN DEFAULT false;
ALTER TABLE payments.billing_profiles ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE payments.billing_profiles ALTER COLUMN updated_at SET NOT NULL;
