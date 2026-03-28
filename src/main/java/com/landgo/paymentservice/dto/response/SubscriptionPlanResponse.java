package com.landgo.paymentservice.dto.response;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SubscriptionPlanResponse {
    private String id;
    private String name;
    private String description;
    private BigDecimal monthlyPrice;
    private BigDecimal annualPrice;
    private String currency;
    private List<String> features;
    private boolean isPopular;
}
