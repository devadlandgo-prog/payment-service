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
import com.landgo.paymentservice.enums.SubscriptionPlan;
import com.landgo.paymentservice.enums.SubscriptionStatus;
import com.landgo.paymentservice.exception.BadRequestException;
import com.landgo.paymentservice.exception.ResourceNotFoundException;
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

    public List<SubscriptionPlanResponse> getSubscriptionPlans(String category) {
        return planDetailRepository.findAll().stream()
                .filter(com.landgo.paymentservice.entity.SubscriptionPlanDetail::isActive)
                .filter(detail -> category == null || category.isBlank() || 
                        (detail.getPlanCategory() != null && detail.getPlanCategory().equalsIgnoreCase(category)))
                .map(detail -> SubscriptionPlanResponse.builder()
                        .id(detail.getPlanType().name().toLowerCase())
                        .name(detail.getName())
                        .description(detail.getDescription())
                        .monthlyPrice(detail.getMonthlyPrice())
                        .annualPrice(detail.getAnnualPrice())
                        .price(detail.getMonthlyPrice())
                        .billingPeriod("MONTHLY")
                        .currency(detail.getCurrency())
                        .features(detail.getFeatures())
                        .maxDuration(detail.getPlanType() == SubscriptionPlan.FREE ? 36500 : 30)
                        .isActive(true)
                        .isPopular(detail.isPopular())
                        .stripeProductId(detail.getStripeProductId())
                        .stripePriceId(detail.getStripePriceId())
                        .build())
                .toList();
    }

    @Transactional
    public Map<String, String> createSubscriptionIntent(UserPrincipal userPrincipal, ProfessionalSubscribeRequest request) {
        return createSubscriptionIntent(userPrincipal.getId(), request);
    }

    @Transactional
    public Map<String, String> createSubscriptionIntent(UUID userId, ProfessionalSubscribeRequest request) {
        subscriptionRepository.findActiveByUserId(userId)
                .ifPresent(sub -> { throw new BadRequestException("User already has an active subscription", "SUBSCRIPTION_ALREADY_ACTIVE"); });

        SubscriptionPlan plan = resolvePlan(request);
        BillingCycle billingCycle = resolveBillingCycle(request);

        com.landgo.paymentservice.entity.SubscriptionPlanDetail detail = planDetailRepository
                .findByPlanTypeAndIsActiveTrue(plan)
                .orElseThrow(() -> new BadRequestException("Invalid or inactive subscription plan", "VALIDATION_ERROR"));

        BigDecimal amount = billingCycle == BillingCycle.ANNUAL
                ? detail.getAnnualPrice()
                : detail.getMonthlyPrice();

        Payment payment = Payment.builder()
                .userId(userId)
                .amount(amount)
                .currency(detail.getCurrency())
                .status(PaymentStatus.PENDING)
                .description("Subscription intent for " + plan)
                .provider("INTERNAL")
                .build();
        payment = paymentRepository.save(payment);

        String paymentIntentId = payment.getId().toString();
        String clientSecret = paymentIntentId + "_secret";
        payment.setProviderTransactionId(paymentIntentId);
        paymentRepository.save(payment);

        log.info("Payment intent created for user {} plan {} cycle {}", userId, plan, billingCycle);
        return Map.of("paymentIntentId", paymentIntentId, "clientSecret", clientSecret);
    }

    private SubscriptionPlan resolvePlan(ProfessionalSubscribeRequest request) {
        if (request.getPlan() != null) {
            return request.getPlan();
        }
        if (request.getPlanId() == null || request.getPlanId().isBlank()) {
            throw new BadRequestException("planId is required", "VALIDATION_ERROR");
        }
        String normalized = request.getPlanId().trim().toUpperCase();
        if (normalized.startsWith("PLAN_")) {
            normalized = normalized.substring(5);
        }
        return switch (normalized) {
            case "FREE" -> SubscriptionPlan.FREE;
            case "BASIC", "BASIC_LISTING" -> SubscriptionPlan.BASIC;
            case "PREMIUM", "PROFESSIONAL" -> SubscriptionPlan.PREMIUM;
            case "ENTERPRISE" -> SubscriptionPlan.ENTERPRISE;
            default -> throw new BadRequestException("Invalid planId", "VALIDATION_ERROR");
        };
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
        subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .ifPresent(sub -> {
                    log.warn("Subscription failed: User {} already has an active subscription", userPrincipal.getId());
                    throw new BadRequestException("User already has an active subscription", "SUBSCRIPTION_ALREADY_ACTIVE");
                });

        SubscriptionPlan planType = resolvePlan(request);
        com.landgo.paymentservice.entity.SubscriptionPlanDetail detail = planDetailRepository.findByPlanTypeAndIsActiveTrue(planType)
                .orElseThrow(() -> new BadRequestException("Invalid or inactive subscription plan", "VALIDATION_ERROR"));

        log.debug("Processing payment for user {} using method {}", userPrincipal.getId(), request.getPaymentMethodId());

        try {
            String customerId = stripeService.getOrCreateCustomer(userPrincipal);
            String stripeSubscriptionId = null;

            if (detail.getStripePriceId() != null && !detail.getStripePriceId().isBlank()) {
                com.stripe.model.Subscription stripeSub = stripeService.createSubscription(customerId, detail.getStripePriceId());
                stripeSubscriptionId = stripeSub.getId();
            } else {
                log.warn("No Stripe Price ID configured for plan {}, creating local-only subscription.", planType);
            }

            LocalDateTime now = LocalDateTime.now();
            int durationDays = planType == SubscriptionPlan.FREE ? 36500 : 30;
            String paymentMethod = request.getPaymentMethod() != null ? request.getPaymentMethod() : request.getPaymentMethodId();
            
            Subscription subscription = Subscription.builder()
                    .userId(userPrincipal.getId())
                    .plan(planType)
                    .status(SubscriptionStatus.ACTIVE)
                    .startDate(now)
                    .endDate(now.plusDays(durationDays))
                    .amount(detail.getMonthlyPrice())
                    .paymentMethod(paymentMethod)
                    .autoRenew(request.isAutoRenew())
                    .maxVendorViewsPerMonth(detail.getMaxVendorViews())
                    .maxSavedLands(detail.getMaxSavedLands())
                    .canAccessPremiumListings(detail.isCanAccessPremium())
                    .canContactVendorDirectly(detail.isCanContactVendor())
                    .stripeSubscriptionId(stripeSubscriptionId)
                    .build();

            subscription = subscriptionRepository.save(subscription);
            log.info("Subscription activated: {} for user: {}", subscription.getId(), userPrincipal.getId());
            return subscriptionMapper.toResponse(subscription);
        } catch (Exception e) {
            log.error("Failed to create Stripe subscription", e);
            throw new BadRequestException("Failed to process subscription payment: " + e.getMessage(), "PAYMENT_FAILED");
        }
    }

    private SubscriptionPlan resolvePlan(SubscriptionRequest request) {
        if (request.getPlan() != null) return request.getPlan();
        if (request.getPlanId() == null || request.getPlanId().isBlank()) {
            throw new BadRequestException("Subscription plan is required", "VALIDATION_ERROR");
        }
        String normalized = request.getPlanId().trim().toUpperCase();
        if (normalized.startsWith("PLAN_")) normalized = normalized.substring(5);
        return switch (normalized) {
            case "FREE" -> SubscriptionPlan.FREE;
            case "BASIC", "BASIC_LISTING" -> SubscriptionPlan.BASIC;
            case "PREMIUM", "PROFESSIONAL" -> SubscriptionPlan.PREMIUM;
            case "ENTERPRISE" -> SubscriptionPlan.ENTERPRISE;
            default -> throw new BadRequestException("Invalid planId", "VALIDATION_ERROR");
        };
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getCurrentSubscription(UserPrincipal userPrincipal) {
        Subscription subscription = subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found"));
        return subscriptionMapper.toResponse(subscription);
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
        if (subscription.getPlan() == request.getPlan())
            throw new BadRequestException("You are already on this plan", "SUBSCRIPTION_SAME_PLAN");
        
        com.landgo.paymentservice.entity.SubscriptionPlanDetail detail = planDetailRepository.findByPlanTypeAndIsActiveTrue(request.getPlan())
                .orElseThrow(() -> new BadRequestException("Invalid or inactive subscription plan", "VALIDATION_ERROR"));

        subscription.setPlan(request.getPlan());
        subscription.setAmount(detail.getMonthlyPrice());
        subscription.setMaxVendorViewsPerMonth(detail.getMaxVendorViews());
        subscription.setMaxSavedLands(detail.getMaxSavedLands());
        subscription.setCanAccessPremiumListings(detail.isCanAccessPremium());
        subscription.setCanContactVendorDirectly(detail.isCanContactVendor());
        
        subscription = subscriptionRepository.save(subscription);
        log.info("Plan changed for user {} to {}", userPrincipal.getId(), request.getPlan());
        return subscriptionMapper.toResponse(subscription);
    }

    @Transactional
    public com.landgo.paymentservice.entity.SubscriptionPlanDetail savePlanDetail(com.landgo.paymentservice.entity.SubscriptionPlanDetail plan) {
        log.info("Transaction BEGIN: Saving new plan detail: {}", plan.getName());
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
    public com.landgo.paymentservice.entity.SubscriptionPlanDetail updatePlanDetail(UUID id, com.landgo.paymentservice.entity.SubscriptionPlanDetail updated) {
        log.info("Transaction BEGIN: Updating plan detail: {}", id);
        com.landgo.paymentservice.entity.SubscriptionPlanDetail plan = planDetailRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));
        
        plan.setName(updated.getName());
        plan.setDescription(updated.getDescription());
        plan.setMonthlyPrice(updated.getMonthlyPrice());
        plan.setAnnualPrice(updated.getAnnualPrice());
        plan.setCurrency(updated.getCurrency());
        plan.setFeatures(updated.getFeatures());
        plan.setMaxVendorViews(updated.getMaxVendorViews());
        plan.setMaxSavedLands(updated.getMaxSavedLands());
        plan.setCanAccessPremium(updated.isCanAccessPremium());
        plan.setCanContactVendor(updated.isCanContactVendor());
        plan.setPopular(updated.isPopular());
        
        com.landgo.paymentservice.entity.SubscriptionPlanDetail saved = planDetailRepository.save(plan);
        log.info("Transaction COMMIT: Plan detail updated: {}", id);
        return saved;
    }

    @Transactional(readOnly = true)
    public boolean hasActiveSubscription(UUID userId) {
        return subscriptionRepository.findActiveByUserId(userId).isPresent();
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
