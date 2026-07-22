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
    private final org.springframework.web.client.RestTemplate restTemplate;

    @org.springframework.beans.factory.annotation.Value("${app.services.core-service-url:http://localhost:8082}")
    private String coreServiceUrl;

    @org.springframework.beans.factory.annotation.Value("${app.services.user-service-url:http://localhost:8081}")
    private String userServiceUrl;

    private Map<String, String> getUserInfo(UUID userId) {
        try {
            Map<?, ?> user = restTemplate.getForObject(userServiceUrl + "/internal/users/" + userId, Map.class);
            if (user != null) {
                Map<String, String> info = new HashMap<>();
                info.put("email", (String) user.get("email"));
                info.put("fullName", (String) user.get("fullName"));
                return info;
            }
        } catch (Exception e) {
            log.error("Failed to fetch user info from user-service for userId={}: {}", userId, e.getMessage());
        }
        return null;
    }

    @org.springframework.beans.factory.annotation.Value("${app.mail.logo-url:https://landgo.app/logo_with_tagline.png}")
    private String logoUrl;

    private void sendEmail(String toEmail, String subject, String templateName, Map<String, String> variables) {
        try {
            String htmlBody = renderTemplate(templateName, variables);

            Map<String, Object> payload = new HashMap<>();
            payload.put("toEmail", toEmail);
            payload.put("subject", subject);
            payload.put("htmlBody", htmlBody);
            
            restTemplate.postForObject(userServiceUrl + "/internal/users/email/send", payload, Void.class);
            log.info("Successfully sent internal payment HTML email request for template: {}", templateName);
        } catch (Exception e) {
            log.error("Failed to render/send internal payment email request for template {}: {}", templateName, e.getMessage());
        }
    }

    private String renderTemplate(String templateName, Map<String, String> variables) throws java.io.IOException {
        String templatePath = "email-templates/" + templateName + ".html";
        org.springframework.core.io.ClassPathResource resource = new org.springframework.core.io.ClassPathResource(templatePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Template file not found: " + templatePath);
        }
        String template = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        // Inject logoUrl
        template = template.replace("/static/icon.svg", logoUrl);
        template = template.replace("{{logoUrl}}", logoUrl);

        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue() != null ? entry.getValue() : "";
                template = template.replace("<!-- -->" + key + "<!-- -->", value);
                template = template.replace("{{" + key + "}}", value);
                template = template.replace("${" + key + "}", value);
            }
        }
        return template;
    }

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

        // Only check for active subscription in non-LAND_LISTING categories
        if (!"LAND_LISTING".equalsIgnoreCase(planCategory)) {
            subscriptionRepository.findActiveByUserIdAndPlanCategoryIgnoreCase(userId, planCategory)
                    .ifPresent(sub -> {
                        throw new BadRequestException("Active subscription already exists for type '" + planCategory + "'",
                                "SUBSCRIPTION_ALREADY_ACTIVE_FOR_CATEGORY");
                    });
        }

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

            if ("LAND_LISTING".equalsIgnoreCase(planCategory) && detail.getMaxVendorViews() != null && detail.getMaxVendorViews() > 0) {
                grantUserListingCredits(userId, detail.getMaxVendorViews());
            }

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

        // Only check for active subscription in non-LAND_LISTING categories
        if (!"LAND_LISTING".equalsIgnoreCase(planCategory)) {
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

            if ("LAND_LISTING".equalsIgnoreCase(planCategory) && detail.getMaxVendorViews() != null && detail.getMaxVendorViews() > 0) {
                grantUserListingCredits(userPrincipal.getId(), detail.getMaxVendorViews());
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

    private void grantUserListingCredits(UUID userId, int credits) {
        try {
            restTemplate.put(userServiceUrl + "/internal/users/" + userId + "/add-listing-credits?credits=" + credits, null);
            log.info("Successfully granted {} listing credits to user {} via user-service internal API", credits, userId);
        } catch (Exception e) {
            log.error("Failed to grant listing credits to user {}: {}", userId, e.getMessage(), e);
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
        Subscription subscription = subscriptionRepository
                .findActiveByUserIdAndPlanCategoryIgnoreCase(userPrincipal.getId(), category.trim())
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found for type: " + category));
        return toResponse(subscription);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> getActiveSubscriptions(UserPrincipal userPrincipal) {
        return subscriptionRepository.findAllActiveByUserId(userPrincipal.getId()).stream()
                .map(this::toResponse)
                .collect(java.util.stream.Collectors.toList());
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

    private void cancelSingleSubscription(Subscription subscription, String reason) {
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
            List<Subscription> activeSubs = subscriptionRepository.findAllActiveByUserId(userPrincipal.getId());
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

                    try {
                        Map<String, String> userInfo = getUserInfo(sub.getUserId());
                        if (userInfo != null) {
                            String email = userInfo.get("email");
                            String name = userInfo.get("fullName");
                            
                            java.util.Map<String, String> vars = new java.util.HashMap<>();
                            vars.put("User", name);
                            vars.put("planName", sub.getPlanCategory() != null ? sub.getPlanCategory() : "Professional Plan");
                            vars.put("amountPaid", "$" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toString());
                            vars.put("txnId", paymentIntentId != null ? paymentIntentId : "N/A");
                            vars.put("date", java.time.LocalDate.now().toString());
                            vars.put("renewalDate", sub.getEndDate() != null ? sub.getEndDate().toLocalDate().toString() : java.time.LocalDate.now().plusDays(30).toString());
                            vars.put("renewalAmount", "$" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toString());
                            
                            sendEmail(email, "LandGo - Payment Receipt", "PaymentSuccess", vars);
                            sendEmail(email, "LandGo - Subscription Activated", "SubscriptionActivated", vars);
                        }
                    } catch (Exception e) {
                        log.error("Failed to send subscription payment success emails", e);
                    }
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

                    try {
                        Map<String, String> userInfo = getUserInfo(sub.getUserId());
                        if (userInfo != null) {
                            String email = userInfo.get("email");
                            String name = userInfo.get("fullName");
                            
                            java.util.Map<String, String> vars = new java.util.HashMap<>();
                            vars.put("User", name);
                            vars.put("planName", sub.getPlanCategory() != null ? sub.getPlanCategory() : "Professional Plan");
                            vars.put("amountDue", "$" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toString());
                            
                            sendEmail(email, "ACTION REQUIRED: LandGo Payment Failed", "PaymentRejected", vars);
                        }
                    } catch (Exception e) {
                        log.error("Failed to send subscription payment failed email", e);
                    }
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

                    try {
                        Map<String, String> userInfo = getUserInfo(sub.getUserId());
                        if (userInfo != null) {
                            String email = userInfo.get("email");
                            String name = userInfo.get("fullName");
                            
                            java.util.Map<String, String> vars = new java.util.HashMap<>();
                            vars.put("User", name);
                            vars.put("planName", sub.getPlanCategory() != null ? sub.getPlanCategory() : "Professional Plan");
                            
                            sendEmail(email, "LandGo - Subscription Cancelled", "SubscriptionCanceled", vars);
                        }
                    } catch (Exception e) {
                        log.error("Failed to send subscription cancelled email", e);
                    }
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

    @Transactional(readOnly = true)
    public Map<String, Object> getUserPlanDetails(UUID userId, String category) {
        String targetCategory = (category == null || category.isBlank()) ? "land_listing" : category;
        Optional<Subscription> activeSubOpt = subscriptionRepository.findActiveByUserIdAndPlanCategoryIgnoreCase(userId, targetCategory);
        Map<String, Object> details = new HashMap<>();
        if (activeSubOpt.isPresent()) {
            Subscription sub = activeSubOpt.get();
            details.put("planType", sub.getPlan());
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
        response.setCancelAtPeriodEnd(!subscription.isAutoRenew());
        
        if (subscription.getPlan() != null && subscription.getPlanCategory() != null) {
            String planCategory = subscription.getPlanCategory().trim().toLowerCase();
            if ("land_listing".equals(planCategory)) {
                response.setMaxListings(getMaxListingsForPlan(subscription.getPlan()));
                
                // Fetch slots used from core-service
                try {
                    String url = coreServiceUrl + "/internal/listings/user/" + subscription.getUserId() + "/slots-used";
                    @SuppressWarnings("unchecked")
                    Map<String, Object> coreResp = restTemplate.getForObject(url, Map.class);
                    if (coreResp != null && coreResp.containsKey("slotsUsed")) {
                        Object slots = coreResp.get("slotsUsed");
                        if (slots instanceof Number) {
                            response.setSlotsUsed(((Number) slots).intValue());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch slotsUsed from core-service for user {}: {}", subscription.getUserId(), e.getMessage());
                    response.setSlotsUsed(0);
                }
            } else {
                response.setMaxListings(null);
                response.setSlotsUsed(null);
            }

            planDetailRepository.findByPlanTypeAndPlanCategoryAndIsActiveTrue(subscription.getPlan(), subscription.getPlanCategory())
                .ifPresent(detail -> {
                    response.setPlanId(detail.getId());
                    if (subscription.getAmount() != null) {
                        if (subscription.getAmount().compareTo(detail.getAnnualPrice()) == 0) {
                            response.setBillingCycle("ANNUAL");
                        } else if (subscription.getAmount().compareTo(detail.getMonthlyPrice()) == 0) {
                            response.setBillingCycle("MONTHLY");
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
