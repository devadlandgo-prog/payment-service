SET search_path TO payments;

INSERT INTO subscription_plan_details (
  id,
  plan_type,
  plan_category,
  name,
  description,
  monthly_price,
  annual_price,
  currency,
  max_vendor_views,
  max_saved_lands,
  can_access_premium,
  can_contact_vendor,
  is_popular,
  is_active,
  deleted,
  created_at,
  updated_at
)
VALUES
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'FREE', 'LAND_LISTING', 'Free', 'Basic access to browse listings', 0.00, 0.00, 'CAD', 5, 10, false, false, false, true, false, now(), now()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12', 'BASIC', 'LAND_LISTING', 'Basic', 'Enhanced access with direct vendor contact', 9.99, 99.99, 'CAD', 20, 50, false, true, false, true, false, now(), now()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13', 'PREMIUM', 'LAND_LISTING', 'Premium', 'Full access with premium listings', 29.99, 299.99, 'CAD', 100, 200, true, true, true, true, false, now(), now()),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14', 'ENTERPRISE', 'MARKET_PROFESSION', 'Enterprise', 'Unlimited access for professionals', 99.99, 999.99, 'CAD', -1, -1, true, true, false, true, false, now(), now())
ON CONFLICT (plan_type) DO NOTHING;

INSERT INTO plan_features (plan_id, feature)
VALUES
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'Browse listings'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '5 vendor views/month'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '10 saved lands'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12', 'Everything in Free'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12', '20 vendor views/month'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12', '50 saved lands'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12', 'Direct vendor contact'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13', 'Everything in Basic'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13', '100 vendor views/month'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13', '200 saved lands'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13', 'Premium listings access'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14', 'Everything in Premium'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14', 'Unlimited vendor views'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14', 'Unlimited saved lands'),
  ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14', 'Priority support');
