package com.landgo.paymentservice.dto.request;

import com.landgo.paymentservice.enums.BillingCycle;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProfessionalSubscribeRequest {
    private String plan;
    private BillingCycle billingCycle;
    private String planId;
    private String subscriptionType;
    private String paymentMethodId;
    private String email;
    @Builder.Default
    private boolean autoRenew = true;
}
