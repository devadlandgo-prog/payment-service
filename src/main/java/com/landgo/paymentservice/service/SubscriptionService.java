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
                        .billingPeriod("MONTHLY")
                        .currency(detail.getCurrency())
                        .features(detail.getFeatures() != null ? new java.util.ArrayList<>(detail.getFeatures())
                                : java.util.Collections.emptyList())
                        .maxVendorViews(detail.getMaxVendorViews())
                        .maxSavedLands(detail.getMaxSavedLands())
                        .canAccessPremium(detail.getCanAccessPremium())
                        .canContactVendor(detail.getCanContactVendor())
                        .popular(detail.getPopular())
                        .type(detail.getPlanCategory())
                        .maxDuration("free".equalsIgnoreCase(detail.getPlanType()) ? 36500 : 30)
                        .isActive(true)
                        .stripeProductId(detail.getStripeProductId())
                        .stripePriceId(detail.getStripePriceId())
                        .build())
                .toList();
    }

    @Transactional
    public Map<String, String> createSubscriptionIntent(UserPrincipal userPrincipal,
            ProfessionalSubscribeRequest request) {
        String email = (request.getEmail() != null && !request.getEmail().isBlank()) 
                ? request.getEmail() : userPrincipal.getEmail();
        return createSubscriptionIntent(userPrincipal.getId(), email, request);
    }

    @Transactional
    public Map<String, String> createSubscriptionIntent(UUID userId, String email, ProfessionalSubscribeRequest request) {
        com.landgo.paymentservice.entity.SubscriptionPlanDetail detail = resolvePlanDetail(
                request.getPlanId(), request.getPlan(), request.getPlanCategory());
        String planCategory = detail.getPlanCategory();

        // Only check for active subscription in the same category
        subscriptionRepository.findActiveByUserIdAndPlanCategoryIgnoreCase(userId, planCategory)
                .ifPresent(sub -> {
                    throw new BadRequestException("Active subscription already exists for type '" + planCategory + "'",
                            "SUBSCRIPTION_ALREADY_ACTIVE_FOR_CATEGORY");
                });

        BillingCycle billingCycle = resolveBillingCycle(request);

        String planType = detail.getPlanType();

        BigDecimal amount = billingCycle == BillingCycle.ANNUAL
                ? detail.getAnnualPrice()
                : detail.getMonthlyPrice();

        Subscription subscription = Subscription.builder()
                .userId(userId)
                .plan(planType)
                .planCategory(planCategory)
                .status("free".equalsIgnoreCase(planType) ? SubscriptionStatus.ACTIVE : SubscriptionStatus.PENDING)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(billingCycle == BillingCycle.ANNUAL ? 365 : 30))
                .amount(amount)
                .autoRenew(true)
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

            log.info("Real Stripe PaymentIntent created for user {} plan {} cycle {}. SubID: {}", userId, planType,
                    billingCycle, subscription.getId());
            return Map.of(
                    "paymentIntent", intent.getClientSecret(),
                    "customer", customerId,
                    "ephemeralKey", ephemeralKey,
                    "publishableKey", stripeService.getPublishableKey(),
                    "subscriptionId", subscription.getId().toString());
        } catch (com.stripe.exception.StripeException e) {
            log.error("Failed to create Stripe PaymentIntent for subscription", e);
            throw new RuntimeException("Stripe error: " + e.getMessage());
        }
    }


    private BillingCycle resolveBillingCycle(ProfessionalSubscribeRequest request) {
        if (request.getBillingCycle() != null) {
            return request.getBillingCycle();
        }
        if (request.getSubscriptionType() == null || request.getSubscriptionType().isBlank()) {
            throw new BadRequestException("subscriptionType is required", "VALIDATION_ERROR");
        }
        String normalized = request.getSubscriptionType().trim().toUpperCase();
        return switch (normalized) {
            case "MONTHLY", "MONTH" -> BillingCycle.MONTHLY;
            case "ANNUAL", "YEARLY", "YEAR" -> BillingCycle.ANNUAL;
            default -> throw new BadRequestException("Invalid subscriptionType", "VALIDATION_ERROR");
        };
    }

    @Transactional
    public SubscriptionResponse subscribe(UserPrincipal userPrincipal, SubscriptionRequest request) {
        log.info("Processing subscription for user: {} to plan: {}", userPrincipal.getId(), request.getPlanId());
        
        com.landgo.paymentservice.entity.SubscriptionPlanDetail detail = resolvePlanDetail(
                request.getPlanId(), request.getPlan(), request.getPlanCategory());
        String planCategory = detail.getPlanCategory();

        // Only check for active subscription in the same category
        subscriptionRepository.findActiveByUserIdAndPlanCategory(userPrincipal.getId(), planCategory)
                .ifPresent(sub -> {
                    log.warn("Subscription failed: User {} already has an active subscription in category {}", 
                            userPrincipal.getId(), planCategory);
                    throw new BadRequestException("User already has an active subscription in this category",
                            "SUBSCRIPTION_ALREADY_ACTIVE");
                });

        String planType = detail.getPlanType();

        log.debug("Processing payment for user {} using method {}", userPrincipal.getId(),
                request.getPaymentMethodId());

        try {
            String customerId = stripeService.getOrCreateCustomer(userPrincipal);
            String stripeSubscriptionId = null;

            if (detail.getStripePriceId() != null && !detail.getStripePriceId().isBlank()) {
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
                    .endDate(now.plusDays(durationDays))
                    .amount(detail.getMonthlyPrice())
                    .paymentMethod(paymentMethod)
                    .autoRenew(request.isAutoRenew())
                    .maxVendorViewsPerMonth(detail.getMaxVendorViews())
                    .maxSavedLands(detail.getMaxSavedLands())
                    .canAccessPremiumListings(detail.getCanAccessPremium())
                    .canContactVendorDirectly(detail.getCanContactVendor())
                    .stripeSubscriptionId(stripeSubscriptionId)
                    .build();

            subscription = subscriptionRepository.save(subscription);
            log.info("Subscription activated: {} for user: {} in category: {}", subscription.getId(), 
                    userPrincipal.getId(), planCategory);
            return subscriptionMapper.toResponse(subscription);
        } catch (Exception e) {
            log.error("Failed to create Stripe subscription", e);
            throw new BadRequestException("Failed to process subscription payment: " + e.getMessage(),
                    "PAYMENT_FAILED");
        }
    }


    @Transactional(readOnly = true)
    public SubscriptionResponse getCurrentSubscription(UserPrincipal userPrincipal) {
        Subscription subscription = subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found"));
        return subscriptionMapper.toResponse(subscription);
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getCurrentSubscription(UserPrincipal userPrincipal, String category) {
        if (category == null || category.isBlank()) {
            return getCurrentSubscription(userPrincipal);
        }
        Subscription subscription = subscriptionRepository
                .findActiveByUserIdAndPlanCategoryIgnoreCase(userPrincipal.getId(), category.trim())
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found for type: " + category));
        return subscriptionMapper.toResponse(subscription);
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

    @Transactional
    public void cancelSubscription(UserPrincipal userPrincipal, String reason) {
        log.info("Request to cancel subscription for user: {}. Reason: {}", userPrincipal.getId(), reason);
        Subscription subscription = subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .orElseThrow(() -> {
                    log.warn("Cancel failed: No active subscription for user {}", userPrincipal.getId());
                    return new ResourceNotFoundException("No active subscription found");
                });

        try {
            if (subscription.getStripeSubscriptionId() != null) {
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
        log.info("Subscription {} cancelled for user: {}", subscription.getId(), userPrincipal.getId());
    }

    @Transactional
    public SubscriptionResponse changePlan(UserPrincipal userPrincipal, ChangeSubscriptionRequest request) {
        Subscription subscription = subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found"));
        if (subscription.getPlan() != null && request.getPlan() != null
                && subscription.getPlan().equalsIgnoreCase(request.getPlan()))
            throw new BadRequestException("You are already on this plan", "SUBSCRIPTION_SAME_PLAN");

        com.landgo.paymentservice.entity.SubscriptionPlanDetail detail = resolvePlanDetail(
                request.getPlanId(), request.getPlan(), request.getPlanCategory());

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

        subscription = subscriptionRepository.save(subscription);
        log.info("Plan changed for user {} to {} on {} cycle", userPrincipal.getId(), request.getPlan(),
                request.getBillingCycle());
        return subscriptionMapper.toResponse(subscription);
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
                    log.info("Subscription {} cancelled due to Stripe deletion (userId={})", sub.getId(),
                            sub.getUserId());
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

        com.landgo.paymentservice.entity.SubscriptionPlanDetail saved = planDetailRepository.save(plan);
        log.info("Transaction COMMIT: Plan detail updated: {}", id);
        return saved;
    }

    @Transactional(readOnly = true)
    public boolean hasActiveSubscription(UUID userId) {
        return subscriptionRepository.findActiveByUserId(userId).isPresent();
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
            sub.setStatus(SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(sub);
            log.info("Subscription expired for user: {}", sub.getUserId());
        }
    }
}
