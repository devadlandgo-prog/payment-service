package com.landgo.paymentservice.dto.request;

import com.landgo.paymentservice.enums.BillingCycle;
import com.landgo.paymentservice.enums.SubscriptionPlan;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ChangeSubscriptionRequest {
    @NotNull(message = "Plan is required") private SubscriptionPlan plan;
    @NotNull(message = "Billing cycle is required") private BillingCycle billingCycle;
}
