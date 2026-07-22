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
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b11', 'SINGLE', 'LAND_LISTING', 'Single Listing Plan', 'One-time entry plan for a single property (1 credit)', 199.00, 199.00, 'CAD', 1, 10, true, true, false, true, false, now(), now()),
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b12', 'PROFESSIONAL', 'LAND_LISTING', 'Professional Listing Plan', '3 listing credits for agents and small portfolios', 499.00, 499.00, 'CAD', 3, 50, true, true, true, true, false, now(), now()),
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b13', 'UNLIMITED', 'LAND_LISTING', '15 Listing Plan', '15 listing credits (highest tier one-time purchase)', 2499.00, 2499.00, 'CAD', 15, 200, true, true, false, true, false, now(), now())
ON CONFLICT (plan_type, plan_category) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  monthly_price = EXCLUDED.monthly_price,
  annual_price = EXCLUDED.annual_price,
  max_vendor_views = EXCLUDED.max_vendor_views,
  is_active = true,
  deleted = false,
  updated_at = now();

INSERT INTO plan_features (plan_id, feature)
VALUES
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b11', '1 Listing Credit'),
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b11', 'One-time purchase'),
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b11', 'Existing listings stay live'),
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b12', '3 Listing Credits'),
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b12', 'One-time purchase'),
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b12', 'Existing listings stay live'),
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b13', '15 Listing Credits'),
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b13', 'One-time purchase'),
  ('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b13', 'Highest tier capacity')
ON CONFLICT DO NOTHING;
