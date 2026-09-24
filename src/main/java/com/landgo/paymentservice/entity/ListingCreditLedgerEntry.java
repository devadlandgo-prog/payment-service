package com.landgo.paymentservice.entity;

import com.landgo.paymentservice.enums.ListingCreditEntryType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One immutable movement of land-listing credits.
 *
 * <p>Rows are append-only. A refund or correction adds a {@code REVERSAL}/{@code ADJUSTMENT} row
 * rather than editing the purchase, so the ledger reconstructs the balance at any point in time
 * and keeps the price, plan and payment reference a refund needs.
 *
 * <p>{@link #idempotencyKey} makes a replayed webhook, a retried
 * {@code verify-and-fulfill} or a re-submitted listing a no-op instead of a double movement.
 */
@Entity
@Table(
        name = "listing_credit_ledger",
        uniqueConstraints = @UniqueConstraint(
                name = "listing_credit_ledger_idempotency_key_key",
                columnNames = {"idempotency_key"}),
        indexes = @Index(name = "idx_listing_credit_ledger_user", columnList = "user_id, created_at"))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ListingCreditLedgerEntry extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 30)
    private ListingCreditEntryType entryType;

    /** Signed credit delta: positive for a purchase or top-up, negative for use or reversal. */
    @Column(name = "credits", nullable = false)
    private int credits;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "plan_name", length = 200)
    private String planName;

    @Column(name = "plan_type", length = 50)
    private String planType;

    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 10)
    private String currency;

    /** Stripe PaymentIntent id (or provider reference) for a purchase, refund or reversal. */
    @Column(name = "payment_reference", length = 200)
    private String paymentReference;

    @Column(name = "payment_id")
    private UUID paymentId;

    /** Listing that consumed the credit, for CONSUMPTION rows. */
    @Column(name = "listing_id")
    private UUID listingId;

    /** Admin who made an adjustment or reversal, for audit. */
    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;
}
