package com.landgo.paymentservice.dto.response;

import lombok.*;

import java.util.UUID;

/**
 * Aggregated land-listing credit entitlement.
 *
 * <p>Deliberately carries no renewal or end date: credits are bought outright and never expire,
 * so there is nothing for a client to count down to.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListingCreditBalanceResponse {
    private UUID userId;
    private int creditsPurchased;
    private int creditsUsed;
    private int creditsAvailable;
    /** Always true — restated here so clients do not have to hardcode the rule. */
    @Builder.Default
    private boolean neverExpires = true;
}
