package com.landgo.paymentservice.enums;

/**
 * How a plan is sold.
 *
 * <p>{@code ONE_TIME} plans (land listing credit packages) are bought outright, as many times as
 * the buyer likes; {@code RECURRING} plans (market professional) renew until cancelled. This is
 * what separates the two product lines everywhere downstream — pricing, expiry, cancellation and
 * which lifecycle emails are sent.
 */
public enum BillingModel { ONE_TIME, RECURRING }
