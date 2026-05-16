package com.landgo.paymentservice.dto.request;

import com.landgo.paymentservice.enums.BillingCycle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ChangeSubscriptionRequest {
    @NotBlank(message = "Plan is required") private String plan;
    private String planId;
    private String planCategory;
    @NotNull(message = "Billing cycle is required") private BillingCycle billingCycle;
}
