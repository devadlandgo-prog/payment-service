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
    public void markPaymentSucceeded(UserPrincipal userPrincipal, String providerTransactionId, String planCategory) {
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
            return;
        }

        payment.setStatus(PaymentStatus.SUCCESS);
        if (!alreadyActive) {
            sub.setStatus(com.landgo.paymentservice.enums.SubscriptionStatus.ACTIVE);
            sub.setStartDate(java.time.LocalDateTime.now());
        }
        paymentRepository.save(payment);

        log.info("Marked payment {} as SUCCESS and activated subscription {} for userId={}",
                providerTransactionId, sub.getId(), userPrincipal.getId());
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
