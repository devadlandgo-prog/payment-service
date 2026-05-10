package com.landgo.paymentservice.entity;

import com.landgo.paymentservice.enums.SubscriptionPlan;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "subscription_plan_details")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPlanDetail extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false, unique = true)
    private SubscriptionPlan planType;

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
    private int maxVendorViews;

    @Column(name = "max_saved_lands")
    private int maxSavedLands;

    @Column(name = "can_access_premium")
    private boolean canAccessPremium;

    @Column(name = "can_contact_vendor")
    private boolean canContactVendor;

    @Column(name = "is_popular")
    private boolean isPopular;

    @Column(name = "plan_category", length = 50)
    private String planCategory;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "stripe_product_id", length = 100)
    private String stripeProductId;

    @Column(name = "stripe_price_id", length = 100)
    private String stripePriceId;
}
