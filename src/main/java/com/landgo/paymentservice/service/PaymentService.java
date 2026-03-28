package com.landgo.paymentservice.service;

import com.landgo.paymentservice.dto.response.PageResponse;
import com.landgo.paymentservice.dto.response.PaymentResponse;
import com.landgo.paymentservice.entity.Payment;
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
        return PageResponse.<PaymentResponse>builder()
                .content(page.getContent().stream().map(this::toResponse).toList())
                .pageNumber(page.getNumber()).pageSize(page.getSize())
                .totalElements(page.getTotalElements()).totalPages(page.getTotalPages())
                .first(page.isFirst()).last(page.isLast()).build();
    }

    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.builder()
                .id(payment.getId()).userId(payment.getUserId()).amount(payment.getAmount())
                .currency(payment.getCurrency()).status(payment.getStatus()).description(payment.getDescription())
                .provider(payment.getProvider()).providerTransactionId(payment.getProviderTransactionId())
                .createdAt(payment.getCreatedAt()).build();
    }
}
