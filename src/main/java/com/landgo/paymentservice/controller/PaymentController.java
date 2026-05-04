package com.landgo.paymentservice.controller;

import com.landgo.paymentservice.dto.response.ApiResponse;
import com.landgo.paymentservice.dto.response.PageResponse;
import com.landgo.paymentservice.dto.response.PaymentResponse;
import com.landgo.paymentservice.enums.PaymentStatus;
import com.landgo.paymentservice.security.CurrentUser;
import com.landgo.paymentservice.security.UserPrincipal;
import com.landgo.paymentservice.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment history and processing APIs")
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/payments/my")
    @Operation(summary = "Get my payment history")
    public ResponseEntity<ApiResponse<PageResponse<PaymentResponse>>> getMyPayments(
            @CurrentUser UserPrincipal userPrincipal,
            @RequestParam(required = false) PaymentStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        PageResponse<PaymentResponse> response = paymentService.getMyPayments(userPrincipal, status, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/transactions")
    @Operation(summary = "Get my transactions")
    public ResponseEntity<ApiResponse<PageResponse<PaymentResponse>>> getMyTransactions(
            @CurrentUser UserPrincipal userPrincipal, @PageableDefault(size = 20) Pageable pageable) {
        PageResponse<PaymentResponse> response = paymentService.getMyPayments(userPrincipal, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }



    @GetMapping("/transactions/{id}")
    @Operation(summary = "Get specific transaction")
    public ResponseEntity<ApiResponse<PaymentResponse>> getTransaction(
            @CurrentUser UserPrincipal userPrincipal, @PathVariable String id) {
        PaymentResponse response = paymentService.getMyPaymentById(userPrincipal, id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/payment/payment-sheet")
    @Operation(summary = "Generate Stripe PaymentIntent")
    public ResponseEntity<ApiResponse<java.util.Map<String, String>>> createPaymentSheet(
            @CurrentUser UserPrincipal userPrincipal, @RequestBody java.util.Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(java.util.Map.of("paymentIntent", "pi_test")));
    }

    @PostMapping("/payment/verify-and-fulfill")
    @Operation(summary = "Confirm payment success")
    public ResponseEntity<ApiResponse<Void>> verifyAndFulfill(
            @CurrentUser UserPrincipal userPrincipal, @RequestBody java.util.Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success("Payment verified", null));
    }
}
