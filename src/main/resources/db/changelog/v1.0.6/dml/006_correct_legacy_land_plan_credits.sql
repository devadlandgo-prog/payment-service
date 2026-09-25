SET search_path TO payments;

-- v1.0.5 backfilled listing_credits from max_vendor_views for every plan in the
-- land_listing category. That is correct for the three packages seeded as credit
-- packages in v1.0.4 (SINGLE=1, PROFESSIONAL=3, UNLIMITED=15, where the seed
-- deliberately used max_vendor_views as the credit count), and wrong for the
-- legacy FREE/BASIC/PREMIUM tiers from v1.0.0, where the same column means
-- vendor profile views per month -- their own plan_features rows say
-- "5 vendor views/month".
--
-- The visible effect was a $0 "Free" plan advertising 5 listing credits, and
-- "Basic" advertising 20 for $9.99.
--
-- These three are corrected to the entitlement the pre-credits code actually
-- granted, which was derived from the plan tier in
-- SubscriptionService.getMaxListingsForPlan: FREE 1, BASIC 2, PREMIUM 3. That
-- preserves existing behaviour exactly rather than making a pricing decision
-- here. Whether these browse tiers belong in the land_listing catalogue at all
-- is a product question, deliberately left alone.
UPDATE subscription_plan_details SET listing_credits = 1
 WHERE LOWER(plan_category) = 'land_listing' AND UPPER(plan_type) = 'FREE';

UPDATE subscription_plan_details SET listing_credits = 2
 WHERE LOWER(plan_category) = 'land_listing' AND UPPER(plan_type) = 'BASIC';

UPDATE subscription_plan_details SET listing_credits = 3
 WHERE LOWER(plan_category) = 'land_listing' AND UPPER(plan_type) = 'PREMIUM';

-- Any land plan still without an explicit credit count is unsellable rather than
-- silently granting whatever max_vendor_views happens to hold. The application
-- no longer reads that column for credits.
UPDATE subscription_plan_details
   SET listing_credits = 0
 WHERE LOWER(plan_category) = 'land_listing'
   AND listing_credits IS NULL;
