package com.landgo.paymentservice.dto.response;

import com.landgo.paymentservice.enums.ListingCreditEntryType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** One immutable row of a user's listing-credit history. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ListingCreditLedgerResponse {
    private UUID id;
    private ListingCreditEntryType entryType;
    private int credits;
    private UUID planId;
    private String planName;
    private String planType;
    private BigDecimal amount;
    private String currency;
    private String paymentReference;
    private UUID listingId;
    private String reason;
    private LocalDateTime createdAt;
}
