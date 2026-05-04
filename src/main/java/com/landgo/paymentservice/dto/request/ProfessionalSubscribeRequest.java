package com.landgo.paymentservice.dto.request;

import com.landgo.paymentservice.enums.BillingCycle;
import com.landgo.paymentservice.enums.SubscriptionPlan;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProfessionalSubscribeRequest {
    private SubscriptionPlan plan;
    private BillingCycle billingCycle;
    private String planId;
    private String subscriptionType;
    private String paymentMethodId;
    @Builder.Default
    private boolean autoRenew = true;
}
