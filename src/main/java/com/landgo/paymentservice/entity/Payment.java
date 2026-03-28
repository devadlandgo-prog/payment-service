package com.landgo.paymentservice.entity;

import com.landgo.paymentservice.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name = "payments")
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class Payment extends BaseEntity {
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "amount", nullable = false, precision = 10, scale = 2) private BigDecimal amount;
    @Column(name = "currency", nullable = false, length = 3) @Builder.Default private String currency = "CAD";
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 20) @Builder.Default private PaymentStatus status = PaymentStatus.PENDING;
    @Column(name = "description") private String description;
    @Column(name = "provider", length = 50) private String provider;
    @Column(name = "provider_transaction_id") private String providerTransactionId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "subscription_id") private Subscription subscription;
}
