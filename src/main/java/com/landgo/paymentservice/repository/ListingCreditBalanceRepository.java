package com.landgo.paymentservice.repository;

import com.landgo.paymentservice.entity.ListingCreditBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Balance reads and conditional writes.
 *
 * <p>Every mutation is a native statement rather than a read-modify-write, so
 * concurrent purchases both count and concurrent listing submissions cannot both spend
 * the last credit. They are marked {@code clearAutomatically}/{@code flushAutomatically}
 * because a native update bypasses the persistence context — without that, reading the
 * balance back in the same transaction returns the pre-update entity.
 */
@Repository
public interface ListingCreditBalanceRepository extends JpaRepository<ListingCreditBalance, UUID> {

    /**
     * Adds purchased credits, creating the row if this is the user's first package.
     *
     * <p>Written as an upsert rather than read-modify-write so two purchases that land at the same
     * moment both count — the acceptance criterion is that buying the same package twice doubles
     * the balance.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO listing_credit_balances (user_id, credits_purchased, credits_used, updated_at)
            VALUES (:userId, :credits, 0, NOW())
            ON CONFLICT (user_id) DO UPDATE
               SET credits_purchased = listing_credit_balances.credits_purchased + :credits,
                   updated_at = NOW()
            """, nativeQuery = true)
    int addPurchasedCredits(@Param("userId") UUID userId, @Param("credits") int credits);

    /**
     * Spends one credit if and only if one is available.
     *
     * @return 1 when a credit was taken, 0 when the user had none left
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE listing_credit_balances
               SET credits_used = credits_used + 1,
                   updated_at = NOW()
             WHERE user_id = :userId
               AND credits_used < credits_purchased
            """, nativeQuery = true)
    int consumeOneCredit(@Param("userId") UUID userId);

    /** Returns a consumed credit — only used by an explicit, audited reversal. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE listing_credit_balances
               SET credits_used = GREATEST(0, credits_used - 1),
                   updated_at = NOW()
             WHERE user_id = :userId
            """, nativeQuery = true)
    int releaseOneCredit(@Param("userId") UUID userId);

    /**
     * Applies an administrative correction to the purchased total.
     *
     * <p>Clamped at zero so a mistaken negative adjustment cannot leave a user with a nonsensical
     * negative entitlement, and never below what has already been used.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO listing_credit_balances (user_id, credits_purchased, credits_used, updated_at)
            VALUES (:userId, GREATEST(0, :credits), 0, NOW())
            ON CONFLICT (user_id) DO UPDATE
               SET credits_purchased = GREATEST(
                       listing_credit_balances.credits_used,
                       listing_credit_balances.credits_purchased + :credits),
                   updated_at = NOW()
            """, nativeQuery = true)
    int adjustPurchasedCredits(@Param("userId") UUID userId, @Param("credits") int credits);
}
