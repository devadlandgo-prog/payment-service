package com.landgo.paymentservice.dto.request;

import com.landgo.paymentservice.enums.SubscriptionPlan;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SubscriptionRequest {
    @NotNull(message = "Subscription plan is required") private SubscriptionPlan plan;
    private String paymentMethod;
    private String paymentToken;
    @Builder.Default private boolean autoRenew = false;
}
