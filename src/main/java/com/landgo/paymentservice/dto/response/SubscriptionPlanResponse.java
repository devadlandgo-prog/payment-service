package com.landgo.paymentservice.dto.response;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SubscriptionPlanResponse {
    private String id;       // UUID of the plan entity (for update/delete operations)
    private String planType; // e.g. "free", "basic" (for display/subscription requests)
    private String name;
    private String description;
    private BigDecimal price;
    private String billingPeriod;
    private BigDecimal monthlyPrice;
    private BigDecimal annualPrice;
    private String currency;
    private List<String> features;
    private Integer maxVendorViews;
    private Integer maxSavedLands;
    private Boolean canAccessPremium;
    private Boolean canContactVendor;
    private Boolean popular;
    private String type;
    private Integer maxListings;
    private Integer maxDuration;
    private Boolean isActive;
    private String stripeProductId;
    private String stripePriceId;

    /** ONE_TIME for land listing credit packages, RECURRING for market professional plans. */
    private String billingModel;

    /** Credits one purchase grants. Present only for ONE_TIME land packages. */
    private Integer listingCredits;

    /**
     * Allowed recurring intervals. Empty for a one-time package, which is why the web plan card
     * must not render a monthly/annual toggle for it.
     */
    private java.util.List<String> billingIntervals;
}
