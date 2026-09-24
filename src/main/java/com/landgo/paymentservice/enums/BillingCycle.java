package com.landgo.paymentservice.enums;

/**
 * Billing period for a plan purchase.
 *
 * <p>{@code ONE_TIME} exists for land-listing credit packages, which are bought outright and
 * never renew. Market-profession subscriptions accept only {@code MONTHLY} or {@code ANNUAL}.
 */
public enum BillingCycle { MONTHLY, ANNUAL, ONE_TIME }
