package com.landgo.paymentservice.entity;

import com.landgo.paymentservice.enums.BillingModel;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(
        name = "subscription_plan_details",
        uniqueConstraints = @UniqueConstraint(
                name = "subscription_plan_details_plan_type_category_key",
                columnNames = {"plan_type", "plan_category"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPlanDetail extends BaseEntity {

    @Column(name = "plan_type", nullable = false, length = 50)
    private String planType;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "monthly_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(name = "annual_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal annualPrice;

    @Column(name = "currency", nullable = false, length = 10)
    @Builder.Default
    private String currency = "CAD";

    @ElementCollection
    @CollectionTable(name = "plan_features", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "feature")
    private List<String> features;

    @Column(name = "max_vendor_views")
    private Integer maxVendorViews;

    @Column(name = "max_saved_lands")
    private Integer maxSavedLands;

    @Column(name = "can_access_premium")
    private Boolean canAccessPremium;

    @Column(name = "can_contact_vendor")
    private Boolean canContactVendor;

    @Column(name = "is_popular")
    private Boolean popular;

    @Column(name = "plan_category", length = 50)
    private String planCategory;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "stripe_product_id", length = 100)
    private String stripeProductId;

    @Column(name = "stripe_price_id", length = 100)
    private String stripePriceId;

    /**
     * How this plan is sold. {@code ONE_TIME} land packages grant {@link #listingCredits} and are
     * buyable repeatedly; {@code RECURRING} market plans renew on a monthly or annual cycle.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_model", nullable = false, length = 20)
    @Builder.Default
    private BillingModel billingModel = BillingModel.RECURRING;

    /** Credits a single purchase of this package grants. Land packages only. */
    @Column(name = "listing_credits")
    private Integer listingCredits;

    /**
     * Expertise categories a professional on this plan may select. {@code -1} is unlimited, as it
     * is for {@link #maxVendorViews}; {@code 0} allows none.
     *
     * <p>Defaults to 0 rather than to a permissive value on purpose: a professional plan nobody
     * has configured a cap for blocks selection, which an admin notices, instead of silently
     * granting unlimited expertise.
     */
    @Column(name = "max_expertise_selections", nullable = false)
    @Builder.Default
    private Integer maxExpertiseSelections = 0;

    /**
     * The cap in the shape the API publishes it: {@code null} for unlimited, otherwise the exact
     * allowance. The {@code -1} sentinel never leaves the service.
     */
    public Integer resolveMaxExpertiseSelections() {
        return maxExpertiseSelections == null || maxExpertiseSelections < 0 ? null : maxExpertiseSelections;
    }

    /** Raw stored cap, {@code -1} for unlimited. Use for comparisons, not for responses. */
    public int rawMaxExpertiseSelections() {
        return maxExpertiseSelections == null ? 0 : maxExpertiseSelections;
    }

    /** True for land-listing credit packages, whatever the category string happens to be cased as. */
    public boolean isLandListing() {
        return billingModel == BillingModel.ONE_TIME
                || (planCategory != null && "land_listing".equalsIgnoreCase(planCategory.trim()));
    }

    /**
     * Credits one purchase of this package grants.
     *
     * <p>{@code listing_credits} is the only source. This used to fall back to
     * {@code maxVendorViews} for rows seeded before the column existed, which was wrong: on the
     * legacy FREE/BASIC/PREMIUM tiers that column means vendor profile views per month, and the
     * fallback silently turned "5 vendor views" into "5 listing credits" — advertising five free
     * listings on a $0 plan. Every land plan now has the column populated explicitly.
     */
    public int resolveListingCredits() {
        return listingCredits != null && listingCredits > 0 ? listingCredits : 0;
    }
}
