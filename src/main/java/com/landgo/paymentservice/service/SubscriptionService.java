package com.landgo.paymentservice.service;

import com.landgo.paymentservice.dto.request.ChangeSubscriptionRequest;
import com.landgo.paymentservice.dto.request.ProfessionalSubscribeRequest;
import com.landgo.paymentservice.dto.request.SubscriptionRequest;
import com.landgo.paymentservice.dto.response.SubscriptionPlanResponse;
import com.landgo.paymentservice.dto.response.SubscriptionResponse;
import com.landgo.paymentservice.entity.Subscription;
import com.landgo.paymentservice.entity.Payment;
import com.landgo.paymentservice.enums.BillingCycle;
import com.landgo.paymentservice.enums.PaymentStatus;
import com.landgo.paymentservice.enums.SubscriptionStatus;
import com.landgo.paymentservice.exception.BadRequestException;
import com.landgo.paymentservice.exception.ResourceNotFoundException;
import com.landgo.paymentservice.exception.ConflictException;
import com.landgo.paymentservice.mapper.SubscriptionMapper;
import com.landgo.paymentservice.repository.SubscriptionRepository;
import com.landgo.paymentservice.repository.PaymentRepository;
import com.landgo.paymentservice.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionMapper subscriptionMapper;
    private final com.landgo.paymentservice.repository.SubscriptionPlanDetailRepository planDetailRepository;
    private final PaymentRepository paymentRepository;
    private final StripeService stripeService;
    private final ListingCreditService listingCreditService;
    private final PaymentEmailService paymentEmailService;
    private final org.springframework.web.client.RestTemplate restTemplate;

    /** Plan category for the one-time land listing credit packages. */
    public static final String LAND_LISTING = "land_listing";

    /** Plan category for the recurring marketplace-professional subscriptions. */
    public static final String MARKET_PROFESSION = "market_profession";

    /**
     * Land credits never expire, but {@code subscriptions.end_date} is NOT NULL and the expiry
     * scheduler reads it. A purchase row therefore carries a date far beyond any plausible
     * lifetime rather than a real term, and {@link #processExpiredSubscriptions()} skips the
     * category outright so the value is never load-bearing.
     */
    private static final int LAND_CREDIT_RECORD_YEARS = 100;

    /**
     * Reason the plan-switch flow supplies when it cancels the old plan. The subscriber is not
     * cancelling anything, so the ordinary cancellation email is suppressed for it.
     */
    private static final String PLAN_SWITCH_REASON = "Switching to a different plan";

    static boolean isLandListing(String planCategory) {
        return planCategory != null && LAND_LISTING.equalsIgnoreCase(planCategory.trim());
    }

    @org.springframework.beans.factory.annotation.Value("${app.services.core-service-url:http://localhost:8082}")
    private String coreServiceUrl;

    @org.springframework.beans.factory.annotation.Value("${app.services.user-service-url:http://localhost:8081}")
    private String userServiceUrl;

    @Transactional(readOnly = true)
    public List<SubscriptionPlanResponse> getSubscriptionPlans(String category) {
        String normalizedCategory = category == null ? null : category.trim().toLowerCase();
        return planDetailRepository.findAll().stream()
                .filter(com.landgo.paymentservice.entity.SubscriptionPlanDetail::isActive)
                .filter(detail -> normalizedCategory == null || normalizedCategory.isBlank()
                        || (detail.getPlanCategory() != null && detail.getPlanCategory().trim().equalsIgnoreCase(normalizedCategory)))
                .map(detail -> SubscriptionPlanResponse.builder()
                        .id(detail.getId().toString())
                        .planType(detail.getPlanType().toLowerCase())
                        .name(detail.getName())
                        .description(detail.getDescription())
                        .monthlyPrice(detail.getMonthlyPrice())
                        .annualPrice(detail.getAnnualPrice())
                        .price(detail.getMonthlyPrice())
                        .billingPeriod(detail.isLandListing() ? "ONE_TIME" : "MONTHLY")
                        .currency(detail.getCurrency())
                        .features(detail.getFeatures() != null ? new java.util.ArrayList<>(detail.getFeatures())
                                : java.util.Collections.emptyList())
                        .maxVendorViews(detail.getMaxVendorViews())
                        .maxSavedLands(detail.getMaxSavedLands())
                        .canAccessPremium(detail.getCanAccessPremium())
                        .canContactVendor(detail.getCanContactVendor())
                        .popular(detail.getPopular())
                        .type(detail.getPlanCategory())
                        // maxDuration is a recurring-plan term. A land package has no term at
                        // all, so it is left null rather than implying a 30-day credit expiry.
                        .maxDuration(detail.isLandListing()
                                ? null
                                : ("free".equalsIgnoreCase(detail.getPlanType()) ? 36500 : 30))
                        .billingModel(detail.isLandListing()
                                ? com.landgo.paymentservice.enums.BillingModel.ONE_TIME.name()
                                : com.landgo.paymentservice.enums.BillingModel.RECURRING.name())
                        .listingCredits(detail.isLandListing() ? detail.resolveListingCredits() : null)
                        .billingIntervals(detail.isLandListing()
                                ? java.util.List.of()
                                : java.util.List.of("MONTHLY", "ANNUAL"))
                        .isActive(true)
                        .stripeProductId(detail.getStripeProductId())
                        .stripePriceId(detail.getStripePriceId())
                        .build())
                .toList();
    }

    @Transactional
    public com.landgo.paymentservice.dto.response.SubscriptionIntentResponse createSubscriptionIntent(UserPrincipal userPrincipal,
            ProfessionalSubscribeRequest request) {
        String email = (request.getEmail() != null && !request.getEmail().isBlank()) 
                ? request.getEmail() : userPrincipal.getEmail();
        return createSubscriptionIntent(userPrincipal.getId(), email, request);
    }

    @Transactional
    public com.landgo.paymentservice.dto.response.SubscriptionIntentResponse createSubscriptionIntent(UUID userId, String email, ProfessionalSubscribeRequest request) {
        com.landgo.paymentservice.entity.SubscriptionPlanDetail detail = resolvePlanDetail(
                request.getPlanId(), request.getPlan(), request.getPlanCategory());
        String planCategory = detail.getPlanCategory();
        boolean landListing = detail.isLandListing();

        // A land package is bought outright and can be bought again at any time, so owning one is
        // never a reason to refuse checkout. Recurring categories still allow only one active
        // subscription at a time.
        if (!landListing) {
            subscriptionRepository.findActiveByUserIdAndPlanCategoryIgnoreCase(userId, planCategory)
                    .ifPresent(sub -> {
                        throw new BadRequestException("Active subscription already exists for type '" + planCategory + "'",
                                "SUBSCRIPTION_ALREADY_ACTIVE_FOR_CATEGORY");
                    });
        }

        BillingCycle billingCycle = resolveBillingCycle(request, detail);

        String planType = detail.getPlanType();

        BigDecimal amount = resolveAmount(detail, billingCycle);

        LocalDateTime now = LocalDateTime.now();
        Subscription subscription = Subscription.builder()
                .userId(userId)
                .plan(planType)
                .planCategory(planCategory)
                .status("free".equalsIgnoreCase(planType) ? SubscriptionStatus.ACTIVE : SubscriptionStatus.PENDING)
                .startDate(now)
                .endDate(landListing
                        ? now.plusYears(LAND_CREDIT_RECORD_YEARS)
                        : now.plusDays(billingCycle == BillingCycle.ANNUAL ? 365 : 30))
                .amount(amount)
                // A one-time purchase has nothing to renew.
                .autoRenew(!landListing)
                .maxVendorViewsPerMonth(detail.getMaxVendorViews())
                .maxSavedLands(detail.getMaxSavedLands())
                .canAccessPremiumListings(detail.getCanAccessPremium())
                .canContactVendorDirectly(detail.getCanContactVendor())
                .build();
        subscription = subscriptionRepository.save(subscription);

        Payment payment = Payment.builder()
                .userId(userId)
                .userEmail(email)
                .amount(amount)
                .currency(detail.getCurrency())
                .status(PaymentStatus.PENDING)
                .description("Subscription intent for " + planType)
                .provider("STRIPE")
                .subscription(subscription)
                .build();
        payment = paymentRepository.save(payment);

        try {
            String customerId = stripeService.getOrCreateCustomer(userId, email);
            long amountCent = amount.multiply(BigDecimal.valueOf(100)).longValue();
            com.stripe.model.PaymentIntent intent = stripeService.createPaymentIntent(
                    customerId, amountCent, detail.getCurrency().toLowerCase(), "Subscription for " + planType);
            String ephemeralKey = stripeService.getEphemeralKey(customerId);

            payment.setProviderTransactionId(intent.getId());
            paymentRepository.save(payment);

            // Credits are granted when the payment is confirmed, never here. Creating an intent is
            // not a purchase: the buyer may dismiss the Stripe sheet or have the card declined,
            // and granting on intent handed out free credits to anyone who opened checkout.

            log.info("Real Stripe PaymentIntent created for user {} plan {} cycle {}. SubID: {}", userId, planType,
                    billingCycle, subscription.getId());
            return com.landgo.paymentservice.dto.response.SubscriptionIntentResponse.builder()
                    .paymentIntent(intent.getClientSecret())
                    .customer(customerId)
                    .ephemeralKey(ephemeralKey)
                    .publishableKey(stripeService.getPublishableKey())
                    .subscriptionId(subscription.getId())
                    .build();
        } catch (com.stripe.exception.StripeException e) {
            log.error("Failed to create Stripe PaymentIntent for subscription", e);
            throw new RuntimeException("Stripe error: " + e.getMessage());
        }
    }


    /**
     * Resolves the billing cycle for a checkout against what the plan actually supports.
     *
     * <p>A land package is always {@code ONE_TIME} — the client need not send anything, and a
     * monthly/annual value for one is a bug worth surfacing rather than silently honouring.
     * Market plans accept only {@code MONTHLY} or {@code ANNUAL}.
     */
    private BillingCycle resolveBillingCycle(ProfessionalSubscribeRequest request,
                                             com.landgo.paymentservice.entity.SubscriptionPlanDetail detail) {
        boolean landListing = detail != null && detail.isLandListing();

        BillingCycle requested = request.getBillingCycle();
        if (requested == null && request.getSubscriptionType() != null
                && !request.getSubscriptionType().isBlank()) {
            requested = switch (request.getSubscriptionType().trim().toUpperCase()) {
                case "MONTHLY", "MONTH" -> BillingCycle.MONTHLY;
                case "ANNUAL", "YEARLY", "YEAR" -> BillingCycle.ANNUAL;
                case "ONE_TIME", "ONETIME", "ONE-TIME", "ONCE" -> BillingCycle.ONE_TIME;
                default -> throw new BadRequestException("Invalid subscriptionType", "VALIDATION_ERROR");
            };
        }

        if (landListing) {
            // A deployed client that predates one-time billing still sends MONTHLY here. The
            // purchase is one-time regardless of what it asked for, so the value is overridden
            // rather than rejected — failing would break land checkout for every app version
            // already in the wild.
            if (requested != null && requested != BillingCycle.ONE_TIME) {
                log.info("Ignoring {} billing cycle on a one-time land package checkout", requested);
            }
            return BillingCycle.ONE_TIME;
        }

        if (requested == null) {
            throw new BadRequestException("subscriptionType is required", "VALIDATION_ERROR");
        }
        if (requested == BillingCycle.ONE_TIME) {
            throw new BadRequestException(
                    "Recurring plans must be billed MONTHLY or ANNUAL", "VALIDATION_ERROR");
        }
        return requested;
    }

    /** Price for a checkout: a one-time package has a single price, whichever column holds it. */
    private BigDecimal resolveAmount(com.landgo.paymentservice.entity.SubscriptionPlanDetail detail,
                                     BillingCycle billingCycle) {
        if (detail.isLandListing()) {
            return detail.getMonthlyPrice() != null ? detail.getMonthlyPrice() : detail.getAnnualPrice();
        }
        return billingCycle == BillingCycle.ANNUAL ? detail.getAnnualPrice() : detail.getMonthlyPrice();
    }

    @Transactional
    public SubscriptionResponse subscribe(UserPrincipal userPrincipal, SubscriptionRequest request) {
        log.info("Processing subscription for user: {} to plan: {}", userPrincipal.getId(), request.getPlanId());
        
        com.landgo.paymentservice.entity.SubscriptionPlanDetail detail = resolvePlanDetail(
                request.getPlanId(), request.getPlan(), request.getPlanCategory());
        String planCategory = detail.getPlanCategory();

        boolean landListing = detail.isLandListing();

        // Land packages are repeat-purchasable by design; only recurring categories are limited
        // to one active subscription.
        if (!landListing) {
            subscriptionRepository.findActiveByUserIdAndPlanCategoryIgnoreCase(userPrincipal.getId(), planCategory)
                    .ifPresent(sub -> {
                        log.warn("Subscription failed: User {} already has an active subscription in category {}", 
                                userPrincipal.getId(), planCategory);
                        throw new BadRequestException("User already has an active subscription in this category",
                                "SUBSCRIPTION_ALREADY_ACTIVE");
                    });
        }

        String planType = detail.getPlanType();

        log.debug("Processing payment for user {} using method {}", userPrincipal.getId(),
                request.getPaymentMethodId());

        try {
            String customerId = stripeService.getOrCreateCustomer(userPrincipal);
            String stripeSubscriptionId = null;

            // A one-time package must never create a Stripe Subscription — that would set up a
            // recurring charge for something the buyer paid for once.
            if (!landListing && detail.getStripePriceId() != null && !detail.getStripePriceId().isBlank()) {
                com.stripe.model.Subscription stripeSub = stripeService.createSubscription(customerId,
                        detail.getStripePriceId());
                stripeSubscriptionId = stripeSub.getId();
            } else {
                log.warn("No Stripe Price ID configured for plan {}, creating local-only subscription.", planType);
            }

            LocalDateTime now = LocalDateTime.now();
            int durationDays = "free".equalsIgnoreCase(planType) ? 36500 : 30;
            String paymentMethod = request.getPaymentMethod() != null ? request.getPaymentMethod()
                    : request.getPaymentMethodId();

            Subscription subscription = Subscription.builder()
                    .userId(userPrincipal.getId())
                    .plan(planType)
                    .planCategory(planCategory)
                    .status(SubscriptionStatus.ACTIVE)
                    .startDate(now)
                    .endDate(landListing ? now.plusYears(LAND_CREDIT_RECORD_YEARS) : now.plusDays(durationDays))
                    .amount(detail.getMonthlyPrice())
                    .paymentMethod(paymentMethod)
                    .autoRenew(!landListing && request.isAutoRenew())
                    .maxVendorViewsPerMonth(detail.getMaxVendorViews())
                    .maxSavedLands(detail.getMaxSavedLands())
                    .canAccessPremiumListings(detail.getCanAccessPremium())
                    .canContactVendorDirectly(detail.getCanContactVendor())
                    .stripeSubscriptionId(stripeSubscriptionId)
                    .build();

            subscription = subscriptionRepository.save(subscription);

            if (landListing) {
                grantLandListingCredits(subscription, detail, null);
            }

            log.info("Subscription activated: {} for user: {} in category: {}", subscription.getId(), 
                    userPrincipal.getId(), planCategory);
            return toResponse(subscription);
        } catch (Exception e) {
            log.error("Failed to create Stripe subscription", e);
            throw new BadRequestException("Failed to process subscription payment: " + e.getMessage(),
                    "PAYMENT_FAILED");
        }
    }

    /**
     * Credits a completed land-listing purchase.
     *
     * <p>Keyed on the subscription (purchase) id, so a replayed webhook, a retried
     * {@code verify-and-fulfill} and the direct {@code subscribe} path can all call this for the
     * same purchase without granting twice.
     *
     * @return the resulting balance, or null when the plan grants no credits
     */
    public com.landgo.paymentservice.dto.response.ListingCreditBalanceResponse grantLandListingCredits(
            Subscription subscription,
            com.landgo.paymentservice.entity.SubscriptionPlanDetail detail,
            Payment payment) {
        int credits = detail != null ? detail.resolveListingCredits() : 0;
        if (credits <= 0) {
            log.warn("Land plan {} grants no listing credits — nothing to add for subscription {}",
                    detail != null ? detail.getPlanType() : "unknown", subscription.getId());
            return null;
        }

        com.landgo.paymentservice.dto.response.ListingCreditBalanceResponse balance =
                listingCreditService.grantPurchasedCredits(
                        subscription.getUserId(), detail, payment, credits,
                        "purchase.subscription:" + subscription.getId());

        // Mirror the purchased total onto the user record so older clients reading maxListings
        // still see a sane number. The ledger, not this field, gates listing creation.
        mirrorPurchasedCreditsToUserService(subscription.getUserId(), balance.getCreditsPurchased());
        return balance;
    }

    private void mirrorPurchasedCreditsToUserService(UUID userId, int creditsPurchased) {
        try {
            restTemplate.put(userServiceUrl + "/internal/users/" + userId
                    + "/listing-credits?creditsPurchased=" + creditsPurchased, null);
        } catch (Exception e) {
            log.warn("Could not mirror listing credit total to user-service for user {}: {}",
                    userId, e.getMessage());
        }
    }


    @Transactional(readOnly = true)
    public SubscriptionResponse getCurrentSubscription(UserPrincipal userPrincipal) {
        Subscription subscription = subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found"));
        return toResponse(subscription);
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getCurrentSubscription(UserPrincipal userPrincipal, String category) {
        if (category == null || category.isBlank()) {
            return getCurrentSubscription(userPrincipal);
        }
        if (isLandListing(category)) {
            List<Subscription> land = subscriptionRepository
                    .findAllActiveByUserIdAndPlanCategoryIgnoreCase(userPrincipal.getId(), category.trim());
            return aggregatedLandCredits(userPrincipal.getId(), land)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No land listing credits purchased yet"));
        }
        Subscription subscription = subscriptionRepository
                .findActiveByUserIdAndPlanCategoryIgnoreCase(userPrincipal.getId(), category.trim())
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found for type: " + category));
        return toResponse(subscription);
    }

    /**
     * Everything the user currently holds: their recurring subscriptions plus, at most, one
     * aggregated land-credit entitlement.
     *
     * <p>A user may have bought the same land package five times. Those are five purchases, not
     * five subscriptions, and listing them individually made the balance look like five competing
     * plans. They collapse into a single entry carrying the summed credits.
     */
    @Transactional(readOnly = true)
    public List<SubscriptionResponse> getActiveSubscriptions(UserPrincipal userPrincipal) {
        List<Subscription> active = subscriptionRepository.findAllActiveByUserId(userPrincipal.getId());

        List<SubscriptionResponse> responses = active.stream()
                .filter(sub -> !isLandListing(sub.getPlanCategory()))
                .map(this::toResponse)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        aggregatedLandCredits(userPrincipal.getId(), active).ifPresent(responses::add);
        return responses;
    }

    /**
     * One entry summarising every land package the user has bought.
     *
     * <p>Present whenever the user has ever bought credits — including after they have spent them
     * all, so the UI can say "0 remaining, buy more" rather than showing nothing.
     */
    private Optional<SubscriptionResponse> aggregatedLandCredits(UUID userId, List<Subscription> activeSubscriptions) {
        com.landgo.paymentservice.dto.response.ListingCreditBalanceResponse balance =
                listingCreditService.getBalance(userId);
        if (balance.getCreditsPurchased() <= 0) {
            return Optional.empty();
        }

        Subscription mostRecent = activeSubscriptions.stream()
                .filter(sub -> isLandListing(sub.getPlanCategory()))
                .findFirst()
                .orElse(null);

        return Optional.of(SubscriptionResponse.builder()
                .id(mostRecent != null ? mostRecent.getId() : null)
                .plan(mostRecent != null ? mostRecent.getPlan() : "LAND_CREDITS")
                .type(LAND_LISTING)
                .productType(LAND_LISTING)
                .planId(mostRecent != null ? null : null)
                .status(SubscriptionStatus.ACTIVE)
                .startDate(mostRecent != null ? mostRecent.getStartDate() : null)
                // No endDate, nextBillingDate, autoRenew or cancelAtPeriodEnd: credits are bought
                // outright and never lapse.
                .isActive(true)
                .autoRenew(false)
                .cancellable(false)
                .billingModel(com.landgo.paymentservice.enums.BillingModel.ONE_TIME.name())
                .billingCycle(BillingCycle.ONE_TIME.name())
                .creditsPurchased(balance.getCreditsPurchased())
                .creditsUsed(balance.getCreditsUsed())
                .creditsAvailable(balance.getCreditsAvailable())
                .creditsNeverExpire(true)
                .maxListings(balance.getCreditsPurchased())
                .slotsUsed(balance.getCreditsUsed())
                .build());
    }



    private com.landgo.paymentservice.entity.SubscriptionPlanDetail resolvePlanDetail(
            String planId,
            String planType,
            String category) {
        String normalizedCategory = category == null ? null : category.trim().toLowerCase();

        if (planId != null && !planId.isBlank()) {
            try {
                com.landgo.paymentservice.entity.SubscriptionPlanDetail detail = planDetailRepository
                        .findByIdAndIsActiveTrue(java.util.UUID.fromString(planId))
                        .orElseThrow(() -> new BadRequestException("Invalid or inactive subscription plan",
                                "VALIDATION_ERROR"));

                if (normalizedCategory != null && !normalizedCategory.isBlank()) {
                    String detailCategory = detail.getPlanCategory() == null ? "" : detail.getPlanCategory().trim().toLowerCase();
                    if (!detailCategory.equals(normalizedCategory)) {
                        throw new BadRequestException(
                                "Provided planId does not belong to category '" + category + "'",
                                "VALIDATION_ERROR");
                    }
                }

                return detail;
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("planId must be a valid UUID", "VALIDATION_ERROR");
            }
        }

        if (planType == null || planType.isBlank()) {
            throw new BadRequestException("plan or planId is required", "VALIDATION_ERROR");
        }

        if (normalizedCategory != null && !normalizedCategory.isBlank()) {
            return planDetailRepository.findByPlanTypeAndPlanCategoryAndIsActiveTrue(planType, normalizedCategory)
                    .orElseThrow(() -> new BadRequestException(
                            "No active plan found for type '" + planType + "' and category '" + category + "'",
                            "VALIDATION_ERROR"));
        }

        List<com.landgo.paymentservice.entity.SubscriptionPlanDetail> matches = planDetailRepository
                .findAllByPlanTypeAndIsActiveTrue(planType);
        if (matches.isEmpty()) {
            throw new BadRequestException("Invalid or inactive subscription plan", "VALIDATION_ERROR");
        }
        if (matches.size() > 1) {
            throw new BadRequestException(
                    "Multiple active plans exist for plan type '" + planType
                            + "'. Provide planId or planCategory to disambiguate.",
                    "PLAN_SELECTION_AMBIGUOUS");
        }
        return matches.get(0);
    }

    /**
     * Land credits cannot be cancelled: they were bought outright and never renew. Cancelling one
     * would be indistinguishable from confiscating paid-for credits.
     */
    private void rejectIfLandListing(Subscription subscription) {
        if (isLandListing(subscription.getPlanCategory())) {
            throw new BadRequestException(
                    "Land listing credits are a one-time purchase and cannot be cancelled. "
                            + "They never expire, and unused credits remain available.",
                    "LAND_CREDITS_NOT_CANCELLABLE");
        }
    }

    private void cancelSingleSubscription(Subscription subscription, String reason) {
        rejectIfLandListing(subscription);
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
            return;
        }
        try {
            if (subscription.getStripeSubscriptionId() != null && !subscription.getStripeSubscriptionId().isBlank()) {
                stripeService.cancelSubscription(subscription.getStripeSubscriptionId());
                log.info("Successfully cancelled Stripe subscription: {}", subscription.getStripeSubscriptionId());
            }
        } catch (Exception e) {
            log.error("Failed to cancel Stripe subscription, continuing local cancellation", e);
        }
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(LocalDateTime.now());
        subscription.setCancellationReason(reason);
        subscription.setAutoRenew(false);
        subscriptionRepository.save(subscription);
        log.info("Subscription {} cancelled locally", subscription.getId());

        // A plan switch cancels the old plan as an intermediate step; that is not something the
        // subscriber should be told about, and the switch itself sends its own mail.
        if (!PLAN_SWITCH_REASON.equalsIgnoreCase(reason == null ? "" : reason.trim())) {
            paymentEmailService.sendSubscriptionCancelled(subscription.getUserId(), subscription,
                    "subscription.cancelled:" + subscription.getId());
        }
    }

    @Transactional
    public void cancelSubscription(UserPrincipal userPrincipal, String reason, String type, String planCategory, String id, String subscriptionId) {
        log.info("Request to cancel subscription for user: {}. Reason: {}, type: {}, planCategory: {}, id: {}, subscriptionId: {}", 
                userPrincipal.getId(), reason, type, planCategory, id, subscriptionId);
        
        String targetPlanCategory = planCategory != null ? planCategory : type;
        String targetSubId = subscriptionId != null ? subscriptionId : id;
        
        if (targetSubId != null && !targetSubId.isBlank()) {
            try {
                UUID subId = UUID.fromString(targetSubId.trim());
                Subscription subscription = subscriptionRepository.findById(subId)
                        .orElseThrow(() -> new ResourceNotFoundException("Subscription not found"));
                if (!subscription.getUserId().equals(userPrincipal.getId())) {
                    throw new BadRequestException("You do not own this subscription", "VALIDATION_ERROR");
                }
                cancelSingleSubscription(subscription, reason);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid subscription ID format", "VALIDATION_ERROR");
            }
        } else if (targetPlanCategory != null && !targetPlanCategory.isBlank()) {
            if (isLandListing(targetPlanCategory)) {
                throw new BadRequestException(
                        "Land listing credits are a one-time purchase and cannot be cancelled. "
                                + "They never expire, and unused credits remain available.",
                        "LAND_CREDITS_NOT_CANCELLABLE");
            }
            List<Subscription> activeSubs = subscriptionRepository.findAllActiveByUserIdAndPlanCategoryIgnoreCase(userPrincipal.getId(), targetPlanCategory.trim());
            if (!activeSubs.isEmpty()) {
                for (Subscription sub : activeSubs) {
                    cancelSingleSubscription(sub, reason);
                }
            } else {
                // Check if there is an already cancelled one to return cleanly (idempotent skip)
                List<Subscription> allSubs = subscriptionRepository.findAllByUserIdAndPlanCategoryIgnoreCase(userPrincipal.getId(), targetPlanCategory.trim());
                if (!allSubs.isEmpty() && allSubs.get(0).getStatus() == SubscriptionStatus.CANCELLED) {
                    log.info("Subscription already cancelled in category: {}", targetPlanCategory);
                    return;
                }
                throw new ResourceNotFoundException("No active subscription found to cancel in category: " + targetPlanCategory);
            }
        } else {
            // An untargeted cancel means "cancel my recurring subscriptions". Land credit
            // purchases are skipped rather than rejected, so a blanket cancel still works for a
            // user who also happens to hold credits.
            List<Subscription> activeSubs = subscriptionRepository.findAllActiveByUserId(userPrincipal.getId())
                    .stream()
                    .filter(sub -> !isLandListing(sub.getPlanCategory()))
                    .toList();
            if (!activeSubs.isEmpty()) {
                for (Subscription sub : activeSubs) {
                    cancelSingleSubscription(sub, reason);
                }
            } else {
                throw new ResourceNotFoundException("No active subscription found to cancel");
            }
        }
    }

    @Transactional
    public SubscriptionResponse changePlan(UserPrincipal userPrincipal, ChangeSubscriptionRequest request) {
        String category = request.getPlanCategory();
        if (isLandListing(category)) {
            // There is nothing to switch: a land package is not an ongoing plan. Buying a
            // different package simply adds its credits to the same balance.
            throw new BadRequestException(
                    "Land listing packages are one-time purchases. Buy the package you want; its "
                            + "credits are added to your existing balance.",
                    "LAND_CREDITS_NOT_SWITCHABLE");
        }
        Subscription subscription = (category != null && !category.isBlank())
                ? subscriptionRepository.findActiveByUserIdAndPlanCategoryIgnoreCase(userPrincipal.getId(), category.trim())
                        .orElseThrow(() -> new ResourceNotFoundException("No active subscription found for category: " + category))
                : subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("No active subscription found"));
        if (subscription.getPlan() != null && request.getPlan() != null
                && subscription.getPlan().equalsIgnoreCase(request.getPlan()))
            throw new BadRequestException("You are already on this plan", "SUBSCRIPTION_SAME_PLAN");
 
        com.landgo.paymentservice.entity.SubscriptionPlanDetail detail = resolvePlanDetail(
                request.getPlanId(), request.getPlan(), request.getPlanCategory());

        String previousPlan = subscription.getPlan();
        subscription.setPlan(detail.getPlanType());
        subscription.setAmount(request.getBillingCycle() == BillingCycle.ANNUAL
                ? detail.getAnnualPrice()
                : detail.getMonthlyPrice());
        subscription.setMaxVendorViewsPerMonth(detail.getMaxVendorViews());
        subscription.setMaxSavedLands(detail.getMaxSavedLands());
        subscription.setCanAccessPremiumListings(detail.getCanAccessPremium());
        subscription.setCanContactVendorDirectly(detail.getCanContactVendor());
 
        // Update Stripe subscription if it exists
        if (subscription.getStripeSubscriptionId() != null && detail.getStripePriceId() != null) {
            try {
                stripeService.updateSubscription(subscription.getStripeSubscriptionId(), detail.getStripePriceId());
            } catch (Exception e) {
                log.error("Failed to update Stripe subscription: {}", e.getMessage());
                // Depending on requirements, we might want to throw an exception here
            }
        }
 
        // Extend end date from now based on billing cycle
        LocalDateTime now = LocalDateTime.now();
        int durationDays = request.getBillingCycle() == BillingCycle.ANNUAL ? 365 : 30;
        subscription.setStartDate(now);
        subscription.setEndDate(now.plusDays(durationDays));
 
        Integer oldMaxListings = getMaxListingsForPlan(subscription.getPlan());
        Integer newMaxListings = getMaxListingsForPlan(detail.getPlanType());
        
        subscription = subscriptionRepository.save(subscription);

        // Keyed on the subscription and the plan it landed on, so re-running a switch to the same
        // plan mails once while a later switch to a different plan mails again.
        paymentEmailService.sendPlanSwitched(userPrincipal.getId(), subscription, previousPlan,
                "subscription.switched:" + subscription.getId() + ":" + subscription.getPlan());
        
        if ("land_listing".equalsIgnoreCase(category) && oldMaxListings != null && newMaxListings != null && newMaxListings < oldMaxListings) {
            try {
                restTemplate.postForObject(coreServiceUrl + "/internal/listings/user/" + userPrincipal.getId() + "/downgrade", null, Void.class);
            } catch (Exception e) {
                log.error("Failed to notify core-service of subscription downgrade", e);
            }
        }
        
        log.info("Plan changed for user {} to {} on {} cycle", userPrincipal.getId(), request.getPlan(),
                request.getBillingCycle());
        return toResponse(subscription);
    }

    @Transactional
    public void handleInvoicePaymentSucceeded(String stripeSubscriptionId, String stripeCustomerId, Long amountPaid,
            String currency, String paymentIntentId) {
        subscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
                .ifPresentOrElse(sub -> {
                    sub.setStatus(SubscriptionStatus.ACTIVE);
                    // Extend end date by 30 days or 1 year based on some logic (or just use 30 for
                    // now as default)
                    // In a real app, we'd check the billing cycle from the subscription or invoice
                    if (sub.getEndDate() == null || sub.getEndDate().isBefore(LocalDateTime.now())) {
                        sub.setEndDate(LocalDateTime.now().plusDays(30));
                    }
                    subscriptionRepository.save(sub);
                    log.info("Subscription {} activated/renewed for userId={}", sub.getId(), sub.getUserId());

                    // Record payment
                    BigDecimal amount = amountPaid != null
                            ? BigDecimal.valueOf(amountPaid).divide(BigDecimal.valueOf(100))
                            : sub.getAmount();

                    Payment payment = Payment.builder()
                            .userId(sub.getUserId())
                            .amount(amount)
                            .currency(currency != null ? currency.toUpperCase() : "CAD")
                            .status(PaymentStatus.SUCCESS)
                            .description("Stripe subscription renewal: " + stripeSubscriptionId)
                            .provider("STRIPE")
                            .providerTransactionId(paymentIntentId)
                            .subscription(sub)
                            .build();
                    paymentRepository.save(payment);

                    // Keyed on the Stripe payment intent so a replayed invoice.paid webhook does
                    // not re-send the receipt. Activation is keyed on the subscription, so a
                    // renewal sends a receipt but not a second "activated" mail.
                    String paymentKey = "stripe.invoice.paid:"
                            + (paymentIntentId != null ? paymentIntentId : stripeSubscriptionId);
                    paymentEmailService.sendSubscriptionReceipt(sub.getUserId(), sub, amount,
                            currency, paymentIntentId, paymentKey);
                    paymentEmailService.sendSubscriptionActivated(sub.getUserId(), sub, amount,
                            currency, "subscription.activated:" + sub.getId());
                }, () -> log.warn("Subscription not found for Stripe ID: {}", stripeSubscriptionId));
    }

    @Transactional
    public void handleInvoicePaymentFailed(String stripeSubscriptionId, Long amountDue, String currency,
            String paymentIntentId) {
        subscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
                .ifPresent(sub -> {
                    // We don't necessarily cancel immediately, Stripe may retry
                    // sub.setStatus(SubscriptionStatus.EXPIRED);
                    // subscriptionRepository.save(sub);
                    log.warn("Payment failed for subscription {} (userId={})", sub.getId(), sub.getUserId());

                    BigDecimal amount = amountDue != null
                            ? BigDecimal.valueOf(amountDue).divide(BigDecimal.valueOf(100))
                            : sub.getAmount();

                    Payment payment = Payment.builder()
                            .userId(sub.getUserId())
                            .amount(amount)
                            .currency(currency != null ? currency.toUpperCase() : "CAD")
                            .status(PaymentStatus.FAILED)
                            .description("Stripe payment failed for subscription: " + stripeSubscriptionId)
                            .provider("STRIPE")
                            .providerTransactionId(paymentIntentId)
                            .subscription(sub)
                            .build();
                    paymentRepository.save(payment);

                    paymentEmailService.sendPaymentFailed(sub.getUserId(), sub, amount, currency,
                            "stripe.invoice.failed:"
                                    + (paymentIntentId != null ? paymentIntentId : stripeSubscriptionId));
                });
    }

    @Transactional
    public void handleSubscriptionDeleted(String stripeSubscriptionId) {
        subscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
                .ifPresent(sub -> {
                    sub.setStatus(SubscriptionStatus.CANCELLED);
                    sub.setCancelledAt(LocalDateTime.now());
                    sub.setCancellationReason("Cancelled via Stripe (deleted)");
                    sub.setAutoRenew(false);
                    subscriptionRepository.save(sub);
                    log.info("Subscription {} cancelled due to Stripe deletion (userId={})", sub.getId(), sub.getUserId());

                    paymentEmailService.sendSubscriptionCancelled(sub.getUserId(), sub,
                            "subscription.cancelled:" + sub.getId());
                });
    }

    @Transactional
    public com.landgo.paymentservice.entity.SubscriptionPlanDetail savePlanDetail(
            com.landgo.paymentservice.entity.SubscriptionPlanDetail plan) {
        log.info("Transaction BEGIN: Saving new plan detail: {}", plan.getName());

        String normalizedCategory = plan.getPlanCategory() != null ? plan.getPlanCategory().trim().toLowerCase() : null;
        if (normalizedCategory == null || normalizedCategory.isBlank()) {
            throw new BadRequestException("Plan category (type) is required", "VALIDATION_ERROR");
        }
        plan.setPlanCategory(normalizedCategory);
        validateBillingModel(plan);

        // Check if a plan with this plan_type/type already exists
        Optional<com.landgo.paymentservice.entity.SubscriptionPlanDetail> existingPlan = planDetailRepository
                .findByPlanTypeAndPlanCategory(plan.getPlanType(), normalizedCategory);

        if (existingPlan.isPresent()) {
            throw new ConflictException(
                    "Plan tier " + plan.getPlanType() + " already exists for type " + normalizedCategory
                            + ". Use PUT /subscriptions/plans/{id} to update or choose another tier.",
                    "PLAN_TIER_TYPE_CONFLICT");
        }

        com.landgo.paymentservice.entity.SubscriptionPlanDetail saved = planDetailRepository.save(plan);
        log.info("Transaction COMMIT: Plan detail saved: {}", saved.getId());
        return saved;
    }

    @Transactional
    public void deletePlanDetail(UUID id) {
        com.landgo.paymentservice.entity.SubscriptionPlanDetail plan = planDetailRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));
        plan.setActive(false);
        planDetailRepository.save(plan);
    }

    @Transactional
    public com.landgo.paymentservice.entity.SubscriptionPlanDetail updatePlanDetail(UUID id,
            com.landgo.paymentservice.entity.SubscriptionPlanDetail updated) {
        log.info("Transaction BEGIN: Updating plan detail: {}", id);
        com.landgo.paymentservice.entity.SubscriptionPlanDetail plan = planDetailRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));

        plan.setName(updated.getName());
        plan.setDescription(updated.getDescription());
        plan.setMonthlyPrice(updated.getMonthlyPrice());
        plan.setAnnualPrice(updated.getAnnualPrice());
        plan.setCurrency(updated.getCurrency());
        plan.setFeatures(updated.getFeatures());
        if (updated.getPlanCategory() != null && !updated.getPlanCategory().isBlank()) {
            String normalizedCategory = updated.getPlanCategory().trim().toLowerCase();
            if (!Objects.equals(plan.getPlanCategory(), normalizedCategory)) {
                planDetailRepository.findByPlanTypeAndPlanCategory(plan.getPlanType(), normalizedCategory)
                        .filter(existing -> !existing.getId().equals(plan.getId()))
                        .ifPresent(existing -> {
                            throw new ConflictException(
                                    "Plan tier " + plan.getPlanType() + " already exists for type "
                                            + normalizedCategory + ". Use PUT /subscriptions/plans/{id} to update or choose another tier.",
                                    "PLAN_TIER_TYPE_CONFLICT");
                        });
                plan.setPlanCategory(normalizedCategory);
            }
        }
        if (updated.getMaxVendorViews() != null) {
            plan.setMaxVendorViews(updated.getMaxVendorViews());
        }
        if (updated.getMaxSavedLands() != null) {
            plan.setMaxSavedLands(updated.getMaxSavedLands());
        }
        if (updated.getCanAccessPremium() != null) {
            plan.setCanAccessPremium(updated.getCanAccessPremium());
        }
        if (updated.getCanContactVendor() != null) {
            plan.setCanContactVendor(updated.getCanContactVendor());
        }
        if (updated.getPopular() != null) {
            plan.setPopular(updated.getPopular());
        }
        if (updated.getBillingModel() != null) {
            plan.setBillingModel(updated.getBillingModel());
        }
        if (updated.getListingCredits() != null) {
            plan.setListingCredits(updated.getListingCredits());
        }
        validateBillingModel(plan);

        com.landgo.paymentservice.entity.SubscriptionPlanDetail saved = planDetailRepository.save(plan);
        log.info("Transaction COMMIT: Plan detail updated: {}", id);
        return saved;
    }

    /**
     * Keeps the plan catalogue honest about which product line a plan belongs to.
     *
     * <p>A land package without a credit count is unsellable — checkout would take the money and
     * grant nothing — so it is rejected at save time rather than at the first purchase.
     */
    private void validateBillingModel(com.landgo.paymentservice.entity.SubscriptionPlanDetail plan) {
        boolean landCategory = isLandListing(plan.getPlanCategory());
        if (plan.getBillingModel() == null) {
            plan.setBillingModel(landCategory
                    ? com.landgo.paymentservice.enums.BillingModel.ONE_TIME
                    : com.landgo.paymentservice.enums.BillingModel.RECURRING);
        }

        boolean oneTime = plan.getBillingModel() == com.landgo.paymentservice.enums.BillingModel.ONE_TIME;
        if (landCategory != oneTime) {
            throw new BadRequestException(
                    "Land listing plans must be ONE_TIME and market profession plans must be RECURRING",
                    "VALIDATION_ERROR");
        }

        if (oneTime) {
            if (plan.resolveListingCredits() <= 0) {
                throw new BadRequestException(
                        "listingCredits is required and must be at least 1 for a one-time land package",
                        "VALIDATION_ERROR");
            }
            // Backfill the explicit column for a dashboard still sending only maxVendorViews.
            plan.setListingCredits(plan.resolveListingCredits());
            // A one-time package has a single price. Keeping both columns equal stops a
            // monthly/annual toggle rendering for it by accident.
            if (plan.getMonthlyPrice() != null) {
                plan.setAnnualPrice(plan.getMonthlyPrice());
            }
        } else if (plan.getListingCredits() != null) {
            throw new BadRequestException(
                    "listingCredits applies only to one-time land listing packages",
                    "VALIDATION_ERROR");
        }
    }

    @Transactional(readOnly = true)
    public boolean hasActiveSubscription(UUID userId) {
        return subscriptionRepository.findActiveByUserId(userId).isPresent();
    }

    @Transactional(readOnly = true)
    public boolean hasActiveSubscription(UUID userId, String category) {
        if (category == null || category.isBlank()) {
            return hasActiveSubscription(userId);
        }
        return subscriptionRepository.findActiveByUserIdAndPlanCategoryIgnoreCase(userId, category.trim()).isPresent();
    }

    /**
     * Creates a Stripe Customer Portal session URL.
     */
    @Transactional
    public String createBillingPortalSession(UserPrincipal userPrincipal, String returnUrl) throws Exception {
        String customerId = stripeService.getOrCreateCustomer(userPrincipal);
        com.stripe.model.billingportal.Session session = stripeService.createBillingPortalSession(customerId, returnUrl);
        return session.getUrl();
    }

    /**
     * Validates that the user has an active subscription.
     * Throws ResourceNotFoundException if not.
     */
    @Transactional(readOnly = true)
    public void validateActiveSubscription(UserPrincipal userPrincipal) {
        subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active subscription found. Please subscribe to a plan first."));
    }

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void processExpiredSubscriptions() {
        List<Subscription> expired = subscriptionRepository.findExpiredSubscriptions(LocalDateTime.now());
        for (Subscription sub : expired) {
            // Land credit purchases have no term. Their end_date exists only because the column is
            // NOT NULL, so expiring one would revoke credits the user paid for outright.
            if (isLandListing(sub.getPlanCategory())) {
                continue;
            }
            sub.setStatus(SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(sub);
            log.info("Subscription expired for user: {}", sub.getUserId());
        }
    }

    public Integer getMaxListingsForPlan(String planType) {
        if (planType == null) return 0;
        return switch (planType.trim().toUpperCase()) {
            case "FREE" -> 1;
            case "BASIC" -> 2;
            case "PREMIUM" -> 3;
            default -> 1000000;
        };
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUserPlanDetails(UUID userId) {
        return getUserPlanDetails(userId, "land_listing");
    }

    /**
     * Entitlement summary consumed by core-service before it lets a listing be posted.
     *
     * <p>For land listing this is the aggregated credit balance across every package the user has
     * bought — not one subscription's allowance and not a plan-tier cap, both of which
     * misreported a user who had bought two packages.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getUserPlanDetails(UUID userId, String category) {
        String targetCategory = (category == null || category.isBlank()) ? LAND_LISTING : category;
        Map<String, Object> details = new HashMap<>();

        if (isLandListing(targetCategory)) {
            com.landgo.paymentservice.dto.response.ListingCreditBalanceResponse balance =
                    listingCreditService.getBalance(userId);
            details.put("planType", balance.getCreditsPurchased() > 0 ? "LAND_CREDITS" : "NONE");
            details.put("billingModel", com.landgo.paymentservice.enums.BillingModel.ONE_TIME.name());
            details.put("creditsPurchased", balance.getCreditsPurchased());
            details.put("creditsUsed", balance.getCreditsUsed());
            details.put("creditsAvailable", balance.getCreditsAvailable());
            details.put("creditsNeverExpire", true);
            // Kept for clients still reading maxListings; it is the purchased total, not a cap
            // that resets.
            details.put("maxListings", balance.getCreditsPurchased());
            return details;
        }

        Optional<Subscription> activeSubOpt =
                subscriptionRepository.findActiveByUserIdAndPlanCategoryIgnoreCase(userId, targetCategory);
        if (activeSubOpt.isPresent()) {
            Subscription sub = activeSubOpt.get();
            details.put("planType", sub.getPlan());
            details.put("billingModel", com.landgo.paymentservice.enums.BillingModel.RECURRING.name());
            details.put("maxListings", getMaxListingsForPlan(sub.getPlan()));
        } else {
            details.put("planType", "NONE");
            details.put("maxListings", 0);
        }
        return details;
    }


    private SubscriptionResponse toResponse(Subscription subscription) {
        if (subscription == null) return null;
        SubscriptionResponse response = subscriptionMapper.toResponse(subscription);
        enrichResponse(response, subscription);
        return response;
    }

    private void enrichResponse(SubscriptionResponse response, Subscription subscription) {
        if (subscription == null || response == null) return;
        boolean landListing = isLandListing(subscription.getPlanCategory());

        if (landListing) {
            // Nothing about a one-time purchase renews, cancels or lapses, so every field that
            // would imply otherwise is cleared rather than left with a placeholder date.
            response.setBillingModel(com.landgo.paymentservice.enums.BillingModel.ONE_TIME.name());
            response.setBillingCycle(BillingCycle.ONE_TIME.name());
            response.setCancelAtPeriodEnd(null);
            response.setCancellable(false);
            response.setNextBillingDate(null);
            response.setEndDate(null);
            response.setAutoRenew(false);
            response.setCreditsNeverExpire(true);

            com.landgo.paymentservice.dto.response.ListingCreditBalanceResponse balance =
                    listingCreditService.getBalance(subscription.getUserId());
            response.setCreditsPurchased(balance.getCreditsPurchased());
            response.setCreditsUsed(balance.getCreditsUsed());
            response.setCreditsAvailable(balance.getCreditsAvailable());
            // maxListings/slotsUsed kept populated from the same aggregate for older clients.
            response.setMaxListings(balance.getCreditsPurchased());
            response.setSlotsUsed(balance.getCreditsUsed());
        } else {
            response.setBillingModel(com.landgo.paymentservice.enums.BillingModel.RECURRING.name());
            response.setCancelAtPeriodEnd(!subscription.isAutoRenew());
            response.setCancellable(true);
            response.setNextBillingDate(subscription.isAutoRenew() ? subscription.getEndDate() : null);
            response.setMaxListings(null);
            response.setSlotsUsed(null);
        }

        if (subscription.getPlan() != null && subscription.getPlanCategory() != null) {
            planDetailRepository.findByPlanTypeAndPlanCategoryAndIsActiveTrue(subscription.getPlan(), subscription.getPlanCategory())
                .ifPresent(detail -> {
                    response.setPlanId(detail.getId());
                    if (!landListing && subscription.getAmount() != null) {
                        if (subscription.getAmount().compareTo(detail.getAnnualPrice()) == 0) {
                            response.setBillingCycle("ANNUAL");
                        } else {
                            response.setBillingCycle("MONTHLY");
                        }
                    }
                });
        }
        if (subscription.getPaymentMethod() != null && subscription.getPaymentMethod().startsWith("pm_")) {
            try {
                Map<String, Object> cardDetails = stripeService.getPaymentMethodDetails(subscription.getPaymentMethod());
                if (!cardDetails.isEmpty()) {
                    response.setPaymentMethod(cardDetails);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch Stripe payment method details: {}", e.getMessage());
            }
        }
    }
}
