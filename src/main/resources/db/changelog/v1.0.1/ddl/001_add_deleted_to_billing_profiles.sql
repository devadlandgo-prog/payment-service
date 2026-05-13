-- Add deleted column to billing_profiles table to align with BaseEntity
ALTER TABLE payments.billing_profiles ADD COLUMN deleted BOOLEAN DEFAULT false;
