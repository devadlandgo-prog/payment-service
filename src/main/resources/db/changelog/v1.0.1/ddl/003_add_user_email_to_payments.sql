-- Add user_email column to payments table
ALTER TABLE payments.payments ADD COLUMN user_email VARCHAR(255);
