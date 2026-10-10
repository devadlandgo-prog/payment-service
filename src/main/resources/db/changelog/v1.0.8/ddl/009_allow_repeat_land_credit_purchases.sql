SET search_path TO payments;

-- unique_active_user_category allowed one ACTIVE subscription per (user, category).
-- That is right for market_profession, which really is a subscription: a user holds one
-- plan at a time.
--
-- It is wrong for land_listing. A land package is a one-time credit purchase, not a
-- subscription - buying three credits today and fifteen more next week is the product.
-- Each purchase still writes a subscription row, so the second one collided with the
-- index and fulfilment died with a duplicate key error after Stripe had already taken
-- the money. Eight purchases failed that way in one afternoon.
--
-- The right long-term shape is for a credit purchase not to create a subscription row at
-- all, but that touches the whole payment flow. Narrowing the index restores the product
-- behaviour now and keeps the guarantee where it belongs.
--
-- COALESCE so rows with a NULL category keep the old treatment rather than silently
-- dropping out of the index.
DROP INDEX IF EXISTS unique_active_user_category;

CREATE UNIQUE INDEX unique_active_user_category
    ON subscriptions (user_id, plan_category)
 WHERE status = 'ACTIVE'
   AND COALESCE(lower(plan_category), '') <> 'land_listing';
