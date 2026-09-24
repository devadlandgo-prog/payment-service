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

    /** ONE_TIME for land listing credit purchases, RECURRING for market professional plans. */
    private String billingModel;

    // ── Land listing credits ────────────────────────────────────────────────
    // Populated only for the land_listing product line. These are an aggregate
    // across every package the user has ever bought, not one subscription's
    // allowance, and they never expire — which is why nextBillingDate,
    // cancelAtPeriodEnd and endDate are all null alongside them.

    private Integer creditsPurchased;
    private Integer creditsUsed;
    private Integer creditsAvailable;
    private Boolean creditsNeverExpire;

    /** Next charge date for a recurring plan; null for land credits, which never renew. */
    private LocalDateTime nextBillingDate;

    /** False for land credits: there is no subscription to cancel. */
    private Boolean cancellable;
}

