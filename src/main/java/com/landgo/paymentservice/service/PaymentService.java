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
    public void markPaymentSucceeded(UserPrincipal userPrincipal, String providerTransactionId) {
        paymentRepository.findByProviderTransactionIdAndUserId(providerTransactionId, userPrincipal.getId())
                .ifPresent(payment -> {
                    payment.setStatus(PaymentStatus.SUCCESS);
                    paymentRepository.save(payment);
                    
                    // If this payment is linked to a subscription, activate it
                    if (payment.getSubscription() != null) {
                        com.landgo.paymentservice.entity.Subscription sub = payment.getSubscription();
                        sub.setStatus(com.landgo.paymentservice.enums.SubscriptionStatus.ACTIVE);
                        sub.setStartDate(java.time.LocalDateTime.now());
                        // End date is already set during intent creation, but we could adjust it here if needed
                    }
                    
                    log.info("Marked payment {} as SUCCESS for userId={}", providerTransactionId, userPrincipal.getId());
                });
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
                .id(payment.getId()).userId(payment.getUserId()).amount(payment.getAmount())
                .currency(payment.getCurrency()).status(payment.getStatus()).description(payment.getDescription())
                .provider(payment.getProvider()).providerTransactionId(payment.getProviderTransactionId())
                .createdAt(payment.getCreatedAt()).build();
    }
}
