package com.landgo.paymentservice.dto.response;

import lombok.*;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class VerifyAndFulfillResponse {
    private boolean success;
    private UUID subscriptionId;
}
