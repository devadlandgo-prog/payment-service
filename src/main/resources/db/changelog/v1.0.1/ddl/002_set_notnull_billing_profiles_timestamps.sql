-- Set NOT NULL constraints on created_at and updated_at in billing_profiles to align with BaseEntity
ALTER TABLE payments.billing_profiles ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE payments.billing_profiles ALTER COLUMN updated_at SET NOT NULL;
