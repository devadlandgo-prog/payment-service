package com.landgo.paymentservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/** An auditable manual correction to a user's listing-credit entitlement. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditAdjustmentRequest {

    /** Signed delta — positive grants credits, negative withdraws them. Must not be zero. */
    @NotNull(message = "credits is required")
    private Integer credits;

    @NotBlank(message = "reason is required for an auditable adjustment")
    private String reason;

    /**
     * Optional caller-supplied key. Supply the same value to retry a failed adjustment without
     * risking a double correction.
     */
    private String idempotencyKey;
}
