package com.landgo.paymentservice.entity;

import com.landgo.paymentservice.enums.SubscriptionPlan;
import com.landgo.paymentservice.enums.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name = "subscriptions")
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class Subscription extends BaseEntity {
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(name = "plan", nullable = false, length = 50) private SubscriptionPlan plan;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 50) @Builder.Default private SubscriptionStatus status = SubscriptionStatus.PENDING;
    @Column(name = "start_date", nullable = false) private LocalDateTime startDate;
    @Column(name = "end_date", nullable = false) private LocalDateTime endDate;
    @Column(name = "amount", nullable = false, precision = 10, scale = 2) private BigDecimal amount;
    @Column(name = "payment_method", length = 50) private String paymentMethod;
    @Column(name = "payment_reference") private String paymentReference;
    @Column(name = "auto_renew") @Builder.Default private boolean autoRenew = false;
    @Column(name = "max_vendor_views_per_month") private Integer maxVendorViewsPerMonth;
    @Column(name = "max_saved_lands") private Integer maxSavedLands;
    @Column(name = "can_access_premium_listings") @Builder.Default private boolean canAccessPremiumListings = false;
    @Column(name = "can_contact_vendor_directly") @Builder.Default private boolean canContactVendorDirectly = false;
    @Column(name = "cancelled_at") private LocalDateTime cancelledAt;
    @Column(name = "cancellation_reason", columnDefinition = "TEXT") private String cancellationReason;

    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE && endDate != null && endDate.isAfter(LocalDateTime.now());
    }
}
