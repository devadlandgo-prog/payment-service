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

    @NotNull @DecimalMin("0.0")
    private BigDecimal monthlyPrice;

    @NotNull @DecimalMin("0.0")
    private BigDecimal annualPrice;

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
}
