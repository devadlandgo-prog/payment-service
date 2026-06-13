package com.landgo.paymentservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class VerifyAndFulfillRequest {
    @NotBlank(message = "paymentIntentId must not be blank")
    private String paymentIntentId;
    private String planCategory;
}
