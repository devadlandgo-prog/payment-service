package com.landgo.paymentservice.dto.request;

import com.landgo.paymentservice.enums.BillingCycle;
import com.landgo.paymentservice.enums.SubscriptionPlan;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProfessionalSubscribeRequest {
    @NotNull(message = "Plan is required (BASIC, PREMIUM, ENTERPRISE)") private SubscriptionPlan plan;
    @NotNull(message = "Billing cycle is required (MONTHLY, ANNUAL)") private BillingCycle billingCycle;
}
