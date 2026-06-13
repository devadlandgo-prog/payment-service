package com.landgo.paymentservice.dto.response;

import lombok.*;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SubscriptionIntentResponse {
    private String paymentIntent;
    private String customer;
    private String ephemeralKey;
    private String publishableKey;
    private UUID subscriptionId;
}
