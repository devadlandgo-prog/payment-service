package com.landgo.paymentservice.service;

import com.landgo.paymentservice.dto.response.ListingCreditBalanceResponse;
import com.landgo.paymentservice.dto.response.ListingCreditLedgerResponse;
import com.landgo.paymentservice.dto.response.PageResponse;
import com.landgo.paymentservice.entity.ListingCreditBalance;
import com.landgo.paymentservice.entity.ListingCreditLedgerEntry;
import com.landgo.paymentservice.entity.Payment;
import com.landgo.paymentservice.entity.SubscriptionPlanDetail;
import com.landgo.paymentservice.enums.ListingCreditEntryType;
import com.landgo.paymentservice.exception.BadRequestException;
import com.landgo.paymentservice.exception.ConflictException;
import com.landgo.paymentservice.repository.ListingCreditBalanceRepository;
import com.landgo.paymentservice.repository.ListingCreditLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Land-listing credits: buy them repeatedly, spend them one listing at a time, never lose them.
 *
 * <p>Credits are not a subscription. There is no period, no renewal, no cancellation and no
 * expiry — a user simply buys another package when the balance runs out. Every movement is
 * written to an immutable ledger and mirrored into a balance row that listing creation checks.
 *
 * <p>Every mutation takes an idempotency key. Stripe replays webhooks, mobile retries
 * {@code verify-and-fulfill}, and a listing submission can be re-sent after a timeout; none of
 * those may move the balance twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingCreditService {

    private final ListingCreditBalanceRepository balanceRepository;
    private final ListingCreditLedgerRepository ledgerRepository;

    // ── Reads ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ListingCreditBalanceResponse getBalance(UUID userId) {
        ListingCreditBalance balance = balanceRepository.findById(userId)
                .orElseGet(() -> ListingCreditBalance.builder().userId(userId).build());
        return ListingCreditBalanceResponse.builder()
                .userId(userId)
                .creditsPurchased(balance.getCreditsPurchased())
                .creditsUsed(balance.getCreditsUsed())
                .creditsAvailable(balance.getCreditsAvailable())
                .build();
    }

    @Transactional(readOnly = true)
    public PageResponse<ListingCreditLedgerResponse> getHistory(UUID userId, Pageable pageable) {
        Page<ListingCreditLedgerEntry> page = ledgerRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return PageResponse.<ListingCreditLedgerResponse>builder()
                .content(page.getContent().stream().map(this::toResponse).toList())
                .number(page.getNumber()).size(page.getSize())
                .totalElements(page.getTotalElements()).totalPages(page.getTotalPages())
                .first(page.isFirst()).last(page.isLast())
                .build();
    }

    // ── Movements ───────────────────────────────────────────────────────────

    /**
     * Grants the credits a completed land-listing purchase entitles the buyer to.
     *
     * <p>Runs in its own transaction so a later failure while finishing the payment cannot roll
     * back credits the buyer has already paid for, and returns quietly when the key has been seen
     * before.
     *
     * @return the resulting balance
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ListingCreditBalanceResponse grantPurchasedCredits(
            UUID userId, SubscriptionPlanDetail plan, Payment payment, int credits, String idempotencyKey) {
        if (credits <= 0) {
            log.warn("Refusing to grant a non-positive credit amount ({}) to user {}", credits, userId);
            return getBalance(userId);
        }
        if (ledgerRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Listing credits already granted for key={} userId={} — no-op", idempotencyKey, userId);
            return getBalance(userId);
        }

        balanceRepository.addPurchasedCredits(userId, credits);
        ledgerRepository.save(ListingCreditLedgerEntry.builder()
                .userId(userId)
                .entryType(ListingCreditEntryType.PURCHASE)
                .credits(credits)
                .idempotencyKey(idempotencyKey)
                .planId(plan != null ? plan.getId() : null)
                .planName(plan != null ? plan.getName() : null)
                .planType(plan != null ? plan.getPlanType() : null)
                .amount(payment != null ? payment.getAmount() : null)
                .currency(payment != null ? payment.getCurrency() : (plan != null ? plan.getCurrency() : null))
                .paymentReference(payment != null ? payment.getProviderTransactionId() : null)
                .paymentId(payment != null ? payment.getId() : null)
                .reason("Land listing package purchase")
                .build());

        ListingCreditBalanceResponse balance = getBalance(userId);
        log.info("Granted {} listing credits to user {} (purchased={} used={} available={})",
                credits, userId, balance.getCreditsPurchased(), balance.getCreditsUsed(),
                balance.getCreditsAvailable());
        return balance;
    }

    /**
     * Spends exactly one credit for a listing.
     *
     * <p>The conditional update is the whole guarantee: two listings submitted at once cannot both
     * take the last credit, because only one {@code UPDATE} matches.
     *
     * @throws ConflictException when the user has no credit left
     */
    @Transactional
    public ListingCreditBalanceResponse consumeCredit(UUID userId, UUID listingId, String idempotencyKey) {
        String key = idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey
                : "listing.consume:" + (listingId != null ? listingId : UUID.randomUUID());

        if (ledgerRepository.existsByIdempotencyKey(key)) {
            log.info("Credit already consumed for key={} userId={} — no-op", key, userId);
            return getBalance(userId);
        }

        if (balanceRepository.consumeOneCredit(userId) != 1) {
            throw new ConflictException(
                    "No listing credits available. Buy a land listing package to post another listing.",
                    "NO_LISTING_CREDITS");
        }

        ledgerRepository.save(ListingCreditLedgerEntry.builder()
                .userId(userId)
                .entryType(ListingCreditEntryType.CONSUMPTION)
                .credits(-1)
                .idempotencyKey(key)
                .listingId(listingId)
                .reason("Listing posted")
                .build());

        ListingCreditBalanceResponse balance = getBalance(userId);
        log.info("Consumed 1 listing credit for user {} listing {} (available={})",
                userId, listingId, balance.getCreditsAvailable());
        return balance;
    }

    /**
     * Applies an administrative correction.
     *
     * <p>A negative adjustment never drops the purchased total below what has already been spent:
     * credits that are already backing live listings cannot be taken back by arithmetic.
     */
    @Transactional
    public ListingCreditBalanceResponse adjust(UUID userId, int credits, String reason, UUID actorId,
                                               String idempotencyKey) {
        if (credits == 0) {
            throw new BadRequestException("credits must be non-zero", "VALIDATION_ERROR");
        }
        String key = idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey
                : "listing.adjust:" + UUID.randomUUID();
        if (ledgerRepository.existsByIdempotencyKey(key)) {
            log.info("Credit adjustment already applied for key={} userId={} — no-op", key, userId);
            return getBalance(userId);
        }

        balanceRepository.adjustPurchasedCredits(userId, credits);
        ledgerRepository.save(ListingCreditLedgerEntry.builder()
                .userId(userId)
                .entryType(credits > 0 ? ListingCreditEntryType.ADJUSTMENT : ListingCreditEntryType.REVERSAL)
                .credits(credits)
                .idempotencyKey(key)
                .actorId(actorId)
                .reason(reason)
                .build());

        ListingCreditBalanceResponse balance = getBalance(userId);
        log.info("Admin {} adjusted listing credits for user {} by {} ({}). Available now {}",
                actorId, userId, credits, reason, balance.getCreditsAvailable());
        return balance;
    }

    private ListingCreditLedgerResponse toResponse(ListingCreditLedgerEntry entry) {
        return ListingCreditLedgerResponse.builder()
                .id(entry.getId())
                .entryType(entry.getEntryType())
                .credits(entry.getCredits())
                .planId(entry.getPlanId())
                .planName(entry.getPlanName())
                .planType(entry.getPlanType())
                .amount(entry.getAmount())
                .currency(entry.getCurrency())
                .paymentReference(entry.getPaymentReference())
                .listingId(entry.getListingId())
                .reason(entry.getReason())
                .createdAt(entry.getCreatedAt())
                .build();
    }
}
