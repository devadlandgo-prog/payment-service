package com.landgo.paymentservice.enums;

/**
 * Why a listing-credit ledger row exists.
 *
 * <p>Rows are immutable: a correction is another row, never an edit, so the history stays
 * auditable and refundable.
 */
public enum ListingCreditEntryType {
    /** Credits granted by a completed land-listing package purchase. */
    PURCHASE,
    /** One credit spent posting or activating a listing. */
    CONSUMPTION,
    /** Auditable manual correction by an administrator (may be positive or negative). */
    ADJUSTMENT,
    /** Credits withdrawn because a purchase was refunded or reversed. */
    REVERSAL
}
