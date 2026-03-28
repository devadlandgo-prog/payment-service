package com.landgo.paymentservice.dto.response;

import com.landgo.paymentservice.enums.PaymentStatus;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentResponse {
    private UUID id;
    private UUID userId;
    private BigDecimal amount;
    private String currency;
    private PaymentStatus status;
    private String description;
    private String provider;
    private String providerTransactionId;
    private LocalDateTime createdAt;
}
