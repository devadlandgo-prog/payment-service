package com.landgo.paymentservice.service;

import com.landgo.paymentservice.dto.request.ChangeSubscriptionRequest;
import com.landgo.paymentservice.dto.request.ProfessionalSubscribeRequest;
import com.landgo.paymentservice.dto.request.SubscriptionRequest;
import com.landgo.paymentservice.dto.response.SubscriptionPlanResponse;
import com.landgo.paymentservice.dto.response.SubscriptionResponse;
import com.landgo.paymentservice.entity.Subscription;
import com.landgo.paymentservice.enums.BillingCycle;
import com.landgo.paymentservice.enums.SubscriptionPlan;
import com.landgo.paymentservice.enums.SubscriptionStatus;
import com.landgo.paymentservice.exception.BadRequestException;
import com.landgo.paymentservice.exception.ResourceNotFoundException;
import com.landgo.paymentservice.mapper.SubscriptionMapper;
import com.landgo.paymentservice.repository.SubscriptionRepository;
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

    private record PlanConfig(BigDecimal monthlyPrice, BigDecimal annualPrice,
            int maxVendorViews, int maxSavedLands, boolean canAccessPremium,
            boolean canContactVendor, String description, List<String> features, boolean isPopular) {}

    private static final Map<SubscriptionPlan, PlanConfig> PLAN_CONFIGS = Map.of(
            SubscriptionPlan.FREE, new PlanConfig(BigDecimal.ZERO, BigDecimal.ZERO, 5, 10, false, false,
                    "Basic access to browse listings", List.of("Browse listings", "5 vendor views/month", "10 saved lands"), false),
            SubscriptionPlan.BASIC, new PlanConfig(new BigDecimal("9.99"), new BigDecimal("99.99"), 20, 50, false, true,
                    "Enhanced access with direct vendor contact", List.of("Everything in Free", "20 vendor views/month", "50 saved lands", "Direct vendor contact"), false),
            SubscriptionPlan.PREMIUM, new PlanConfig(new BigDecimal("29.99"), new BigDecimal("299.99"), 100, 200, true, true,
                    "Full access with premium listings", List.of("Everything in Basic", "100 vendor views/month", "200 saved lands", "Premium listings access"), true),
            SubscriptionPlan.ENTERPRISE, new PlanConfig(new BigDecimal("99.99"), new BigDecimal("999.99"), -1, -1, true, true,
                    "Unlimited access for professionals", List.of("Everything in Premium", "Unlimited vendor views", "Unlimited saved lands", "Priority support"), false));

    public List<SubscriptionPlanResponse> getSubscriptionPlans() {
        return Arrays.stream(SubscriptionPlan.values())
                .map(plan -> {
                    PlanConfig c = PLAN_CONFIGS.get(plan);
                    return SubscriptionPlanResponse.builder().id(plan.name().toLowerCase()).name(plan.name())
                            .description(c.description()).monthlyPrice(c.monthlyPrice()).annualPrice(c.annualPrice())
                            .currency("CAD").features(c.features()).isPopular(c.isPopular()).build();
                }).toList();
    }

    @Transactional
    public Map<String, String> createSubscriptionIntent(UserPrincipal userPrincipal, ProfessionalSubscribeRequest request) {
        subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .ifPresent(sub -> { throw new BadRequestException("User already has an active subscription", "SUBSCRIPTION_ALREADY_ACTIVE"); });
        String paymentIntentId = "pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String clientSecret = paymentIntentId + "_secret_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        log.info("Payment intent created for user {} plan {} cycle {}", userPrincipal.getId(), request.getPlan(), request.getBillingCycle());
        return Map.of("paymentIntentId", paymentIntentId, "clientSecret", clientSecret);
    }

    @Transactional
    public SubscriptionResponse subscribe(UserPrincipal userPrincipal, SubscriptionRequest request) {
        subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .ifPresent(sub -> { throw new BadRequestException("User already has an active subscription", "SUBSCRIPTION_ALREADY_ACTIVE"); });
        PlanConfig config = PLAN_CONFIGS.get(request.getPlan());
        LocalDateTime now = LocalDateTime.now();
        int durationDays = request.getPlan() == SubscriptionPlan.FREE ? 36500 : 30;
        Subscription subscription = Subscription.builder()
                .userId(userPrincipal.getId()).plan(request.getPlan()).status(SubscriptionStatus.ACTIVE)
                .startDate(now).endDate(now.plusDays(durationDays)).amount(config.monthlyPrice())
                .paymentMethod(request.getPaymentMethod()).autoRenew(request.isAutoRenew())
                .maxVendorViewsPerMonth(config.maxVendorViews()).maxSavedLands(config.maxSavedLands())
                .canAccessPremiumListings(config.canAccessPremium()).canContactVendorDirectly(config.canContactVendor()).build();
        subscription = subscriptionRepository.save(subscription);
        log.info("User {} subscribed to plan: {}", userPrincipal.getId(), request.getPlan());
        return subscriptionMapper.toResponse(subscription);
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getCurrentSubscription(UserPrincipal userPrincipal) {
        Subscription subscription = subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found"));
        return subscriptionMapper.toResponse(subscription);
    }

    @Transactional
    public void cancelSubscription(UserPrincipal userPrincipal, String reason) {
        Subscription subscription = subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found"));
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(LocalDateTime.now());
        subscription.setCancellationReason(reason);
        subscription.setAutoRenew(false);
        subscriptionRepository.save(subscription);
        log.info("Subscription cancelled for user: {}", userPrincipal.getId());
    }

    @Transactional
    public SubscriptionResponse changePlan(UserPrincipal userPrincipal, ChangeSubscriptionRequest request) {
        Subscription subscription = subscriptionRepository.findActiveByUserId(userPrincipal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found"));
        if (subscription.getPlan() == request.getPlan())
            throw new BadRequestException("You are already on this plan", "SUBSCRIPTION_SAME_PLAN");
        PlanConfig config = PLAN_CONFIGS.get(request.getPlan());
        int durationDays = request.getBillingCycle() == BillingCycle.ANNUAL ? 365 : 30;
        BigDecimal amount = request.getBillingCycle() == BillingCycle.ANNUAL ? config.annualPrice() : config.monthlyPrice();
        subscription.setPlan(request.getPlan());
        subscription.setAmount(amount);
        subscription.setEndDate(LocalDateTime.now().plusDays(durationDays));
        subscription.setMaxVendorViewsPerMonth(config.maxVendorViews());
        subscription.setMaxSavedLands(config.maxSavedLands());
        subscription.setCanAccessPremiumListings(config.canAccessPremium());
        subscription.setCanContactVendorDirectly(config.canContactVendor());
        subscription = subscriptionRepository.save(subscription);
        log.info("Subscription changed for user {} to plan {}", userPrincipal.getId(), request.getPlan());
        return subscriptionMapper.toResponse(subscription);
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
