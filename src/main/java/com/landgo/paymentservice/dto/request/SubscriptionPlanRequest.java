package com.landgo.paymentservice.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SubscriptionPlanRequest {
    @NotBlank
    private String planType;

    @NotBlank(message = "Plan name is required")
    private String name;

    @NotBlank(message = "Description is required")
    private String description;

    /**
     * Recurring monthly price. Required for RECURRING plans; for a ONE_TIME land
     * package this is the single price and {@link #annualPrice} is derived from it.
     */
    @DecimalMin("0.0")
    private BigDecimal monthlyPrice;

    /**
     * Recurring annual price. Required for RECURRING plans only.
     *
     * Deliberately not @NotNull: SubscriptionService already mirrors monthlyPrice
     * into this column for a ONE_TIME package, because a one-time purchase has a
     * single price and keeping the two equal stops a monthly/annual toggle
     * rendering for it. Marking it @NotNull meant bean validation rejected the
     * request before that code could run, so creating a land listing package
     * always returned 400 with "annualPrice: must not be null".
     */
    @DecimalMin("0.0")
    private BigDecimal annualPrice;

    /**
     * Single price for a ONE_TIME package. Accepted as an alias for
     * {@link #monthlyPrice}, which is what a one-time plan actually has - asking a
     * client to send a "monthly" price for a one-off purchase invites exactly the
     * mismatch that produced the 400 above.
     *
     * Additive: clients already sending monthlyPrice are unaffected, and if both
     * are present monthlyPrice wins.
     */
    @DecimalMin("0.0")
    private BigDecimal price;

    @NotBlank
    private String currency;

    private List<String> features;

    private Integer maxVendorViews;
    private Integer maxSavedLands;
    private Boolean canAccessPremium;
    private Boolean canContactVendor;
    private Boolean popular;

    /** market_profession | land_listing */
    @NotBlank
    private String type;

    /**
     * ONE_TIME | RECURRING. Optional — when omitted it is inferred from {@link #type}, so existing
     * dashboard clients keep working.
     */
    private String billingModel;

    /** Credits a single purchase of a land package grants. Required for ONE_TIME plans. */
    @jakarta.validation.constraints.Min(value = 1, message = "listingCredits must be at least 1")
    private Integer listingCredits;

    /** The single price for a one-time package, whichever field the client used. */
    public BigDecimal resolveOneTimePrice() {
        return monthlyPrice != null ? monthlyPrice : price;
    }

    private boolean isOneTimeRequest() {
        if (billingModel != null && !billingModel.isBlank()) {
            return "ONE_TIME".equalsIgnoreCase(billingModel.trim());
        }
        // billingModel is optional and inferred from type, per the field above.
        return "land_listing".equalsIgnoreCase(type == null ? "" : type.trim());
    }

    /**
     * Replaces the blanket @NotNull that used to sit on both price fields. A
     * one-time package needs one price; a recurring plan needs both. Checking it
     * here keeps the error a 400 with a readable message rather than a 500 from a
     * null further down.
     */
    @jakarta.validation.constraints.AssertTrue(
            message = "a ONE_TIME plan requires price (or monthlyPrice); a RECURRING plan requires both monthlyPrice and annualPrice")
    public boolean isPricingValid() {
        if (isOneTimeRequest()) {
            return resolveOneTimePrice() != null;
        }
        return monthlyPrice != null && annualPrice != null;
    }
}
