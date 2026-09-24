package com.landgo.paymentservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Running land-listing credit totals for one user.
 *
 * <p>A denormalised companion to {@link ListingCreditLedgerEntry}: the ledger is the audit
 * record, this row is what gets read on every listing create and conditionally updated to spend a
 * credit. Spending is a single conditional {@code UPDATE ... WHERE credits_used <
 * credits_purchased}, so two concurrent listing submissions cannot both take the last credit.
 *
 * <p>Credits never expire, so there is no period, end date or renewal here — only totals.
 */
@Entity
@Table(name = "listing_credit_balances")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListingCreditBalance {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "credits_purchased", nullable = false)
    @Builder.Default
    private int creditsPurchased = 0;

    @Column(name = "credits_used", nullable = false)
    @Builder.Default
    private int creditsUsed = 0;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    public int getCreditsAvailable() {
        return Math.max(0, creditsPurchased - creditsUsed);
    }
}
