package com.landgo.paymentservice.dto.response;

import com.landgo.paymentservice.enums.SubscriptionStatus;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SubscriptionResponse {
    private UUID id;
    private String plan;
    private String type;
    private SubscriptionStatus status;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private BigDecimal amount;
    private String currency;
    private Object paymentMethod;
    private boolean autoRenew;
    private boolean isActive;
    private Integer maxVendorViewsPerMonth;
    private Integer maxSavedLands;
    private boolean canAccessPremiumListings;
    private boolean canContactVendorDirectly;
    private String stripeSubscriptionId;
    
    private UUID planId;
    private String billingCycle;
    private Boolean cancelAtPeriodEnd;

    private Integer slotsUsed;
    private Integer maxListings;
    private String productType;
}

