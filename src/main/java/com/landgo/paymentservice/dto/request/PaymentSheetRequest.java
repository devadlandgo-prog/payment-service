package com.landgo.paymentservice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    @NotNull(message = "Amount is required")
    private Long amountCent;
    
    @NotBlank(message = "Currency is required")
    private String currency;
    
    private String description;
}
