package com.landgo.paymentservice.dto.request;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SubscriptionRequest {
    private String plan;
    private String planId;
    private String planCategory;
    private String paymentMethod;
    private String paymentMethodId;
    private String paymentToken;
    @Builder.Default private boolean autoRenew = false;
    private BillingAddress billingAddress;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BillingAddress {
        private String street;
        private String city;
        private String postalCode;
    }
}
