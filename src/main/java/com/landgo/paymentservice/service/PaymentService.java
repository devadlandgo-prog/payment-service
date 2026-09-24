package com.landgo.paymentservice.service;

import com.landgo.paymentservice.dto.response.PageResponse;
import com.landgo.paymentservice.dto.response.PaymentResponse;
import com.landgo.paymentservice.entity.Payment;
import com.landgo.paymentservice.enums.PaymentStatus;
import com.landgo.paymentservice.exception.BadRequestException;
import com.landgo.paymentservice.exception.ResourceNotFoundException;
import com.landgo.paymentservice.repository.PaymentRepository;
import com.landgo.paymentservice.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final com.landgo.paymentservice.repository.SubscriptionRepository subscriptionRepository;
    private final com.landgo.paymentservice.repository.SubscriptionPlanDetailRepository planDetailRepository;
    private final SubscriptionService subscriptionService;
    private final PaymentEmailService paymentEmailService;

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> getMyPayments(UserPrincipal userPrincipal, Pageable pageable) {
        Page<Payment> page = paymentRepository.findByUserId(userPrincipal.getId(), pageable);
        return toPageResponse(page);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> getMyPayments(UserPrincipal userPrincipal, PaymentStatus status, Pageable pageable) {
        if (status == null) {
            return getMyPayments(userPrincipal, pageable);
        }
        Page<Payment> page = paymentRepository.findByUserIdAndStatus(userPrincipal.getId(), status, pageable);
        return toPageResponse(page);
    }

    private PageResponse<PaymentResponse> toPageResponse(Page<Payment> page) {
        return PageResponse.<PaymentResponse>builder()
                .content(page.getContent().stream().map(this::toResponse).toList())
                .number(page.getNumber()).size(page.getSize())
                .totalElements(page.getTotalElements()).totalPages(page.getTotalPages())
                .first(page.isFirst()).last(page.isLast()).build();
    }

    @Transactional
    public java.util.UUID markPaymentSucceeded(UserPrincipal userPrincipal, String providerTransactionId, String planCategory) {
        Payment payment = paymentRepository.findByProviderTransactionIdAndUserId(providerTransactionId, userPrincipal.getId())
                .orElseGet(() -> {
                    Payment fallbackPayment = paymentRepository.findByProviderTransactionIdAndDeletedFalse(providerTransactionId)
                            .orElseThrow(() -> new ResourceNotFoundException("No pending payment found for this intent. Ensure /subscriptions/intent was called first."));
                    if (!fallbackPayment.getUserId().equals(userPrincipal.getId())) {
                        log.info("Associating payment {} from user {} to user {}", providerTransactionId, fallbackPayment.getUserId(), userPrincipal.getId());
                        fallbackPayment.setUserId(userPrincipal.getId());
                        if (userPrincipal.getEmail() != null) {
                            fallbackPayment.setUserEmail(userPrincipal.getEmail());
                        }
                    }
                    return fallbackPayment;
                });

        if (payment.getSubscription() == null) {
            throw new BadRequestException("No subscription associated with this payment", "VALIDATION_ERROR");
        }

        com.landgo.paymentservice.entity.Subscription sub = payment.getSubscription();
        if (planCategory != null && !planCategory.isBlank()) {
            String requestedCategory = planCategory.trim().toLowerCase();
            String subscriptionCategory = sub.getPlanCategory() == null ? "" : sub.getPlanCategory().trim().toLowerCase();
            if (!requestedCategory.equals(subscriptionCategory)) {
                log.warn("Fulfill category mismatch for paymentIntentId={} userId={} requestedCategory={} subscriptionCategory={}",
                        providerTransactionId, userPrincipal.getId(), requestedCategory, subscriptionCategory);
                throw new BadRequestException(
                        "Payment does not belong to requested planCategory '" + planCategory + "'",
                        "VALIDATION_ERROR");
            }
        }

        boolean alreadySucceeded = payment.getStatus() == PaymentStatus.SUCCESS;
        boolean alreadyActive = sub.getStatus() == com.landgo.paymentservice.enums.SubscriptionStatus.ACTIVE;
        if (alreadySucceeded && alreadyActive) {
            log.info("Fulfill idempotent success for paymentIntentId={} userId={} subscriptionId={}",
                    providerTransactionId, userPrincipal.getId(), sub.getId());
            // Still run fulfilment: the credit grant and the receipt are keyed on the purchase, so
            // a retry after a partial failure completes rather than silently doing nothing.
            fulfil(sub, payment);
            return sub.getId();
        }

        payment.setStatus(PaymentStatus.SUCCESS);
        if (!alreadyActive) {
            sub.setStatus(com.landgo.paymentservice.enums.SubscriptionStatus.ACTIVE);
            sub.setStartDate(java.time.LocalDateTime.now());
        }
        paymentRepository.save(payment);
        
        // B-BUG-05: Explicitly save the subscription to ensure changes are flushed immediately,
        // avoiding race conditions if clients poll /subscriptions/my before the transaction completes.
        subscriptionRepository.save(sub);

        log.info("Marked payment {} as SUCCESS and activated subscription {} for userId={}",
                providerTransactionId, sub.getId(), userPrincipal.getId());

        fulfil(sub, payment);
        return sub.getId();
    }

    /**
     * Everything that must happen once a payment is confirmed: credits granted, receipt sent.
     *
     * <p>Every step is keyed on the purchase, so calling this again — after a webhook replay, or a
     * client retrying {@code verify-and-fulfill} — grants nothing twice and re-sends nothing.
     *
     * <p>Land credits get a receipt only. A credit purchase is not a subscription, so it must not
     * receive the activation, cancellation or expiry mails.
     */
    private void fulfil(com.landgo.paymentservice.entity.Subscription sub, Payment payment) {
        com.landgo.paymentservice.entity.SubscriptionPlanDetail detail = sub.getPlanCategory() == null
                ? null
                : planDetailRepository
                        .findByPlanTypeAndPlanCategory(sub.getPlan(), sub.getPlanCategory().trim().toLowerCase())
                        .orElse(null);

        boolean landListing = SubscriptionService.isLandListing(sub.getPlanCategory());

        if (landListing) {
            try {
                com.landgo.paymentservice.dto.response.ListingCreditBalanceResponse balance =
                        subscriptionService.grantLandListingCredits(sub, detail, payment);
                if (balance != null) {
                    paymentEmailService.sendLandPurchaseReceipt(
                            sub.getUserId(),
                            detail != null ? detail.getName() : sub.getPlan(),
                            payment.getAmount(), payment.getCurrency(),
                            payment.getProviderTransactionId(),
                            detail != null ? detail.resolveListingCredits() : 0,
                            balance.getCreditsAvailable(),
                            "payment.success:" + payment.getId());
                }
            } catch (Exception e) {
                // The money is taken and the payment row is committed. Surface the failure for
                // operations rather than failing the request the buyer is waiting on.
                log.error("Failed to fulfil land credit purchase for subscription {}: {}",
                        sub.getId(), e.getMessage(), e);
            }
            return;
        }

        paymentEmailService.sendSubscriptionReceipt(sub.getUserId(), sub, payment.getAmount(),
                payment.getCurrency(), payment.getProviderTransactionId(),
                "payment.success:" + payment.getId());
        paymentEmailService.sendSubscriptionActivated(sub.getUserId(), sub, payment.getAmount(),
                payment.getCurrency(), "subscription.activated:" + sub.getId());
    }

    @Transactional(readOnly = true)
    public PaymentResponse getMyPaymentById(UserPrincipal userPrincipal, String id) {
        java.util.UUID paymentId;
        try {
            paymentId = java.util.UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid transaction id", "VALIDATION_ERROR");
        }

        Payment payment = paymentRepository.findByIdAndUserIdAndDeletedFalse(
                        paymentId, userPrincipal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found"));
        return toResponse(payment);
    }

    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.builder()
                .id(payment.getId()).userId(payment.getUserId()).userEmail(payment.getUserEmail())
                .amount(payment.getAmount())
                .currency(payment.getCurrency()).status(payment.getStatus()).description(payment.getDescription())
                .provider(payment.getProvider()).providerTransactionId(payment.getProviderTransactionId())
                .createdAt(payment.getCreatedAt()).build();
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> getAllPayments(Pageable pageable) {
        Page<Payment> page = paymentRepository.findAllPayments(pageable);
        return toPageResponse(page);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> getAllPaymentsByStatus(PaymentStatus status, Pageable pageable) {
        Page<Payment> page = paymentRepository.findAllByStatus(status, pageable);
        return toPageResponse(page);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> getAllPaymentsByProvider(String provider, Pageable pageable) {
        Page<Payment> page = paymentRepository.findAllByProvider(provider, pageable);
        return toPageResponse(page);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> getAllPaymentsByStatusAndProvider(PaymentStatus status, String provider, Pageable pageable) {
        Page<Payment> page = paymentRepository.findAllByStatusAndProvider(status, provider, pageable);
        return toPageResponse(page);
    }
}
