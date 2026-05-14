package com.landgo.paymentservice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSheetRequest {
    @Min(value = 50, message = "Amount must be at least 50 cents")
    @Max(value = 1000000, message = "Amount cannot exceed 1,000,000 cents ($10,000)")
    private long amountCent;
    
    @NotBlank(message = "Currency is required")
    private String currency;
    
    private String description;
}
