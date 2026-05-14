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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.landgo.paymentservice.service.StripeService;
import com.stripe.model.PaymentIntent;

@RestController
@RequestMapping
@Slf4j
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment history and processing APIs")
public class PaymentController {

    private final PaymentService paymentService;
    private final StripeService stripeService;

    @GetMapping("/payments/my")
    @Operation(summary = "Get my payment history")
    public ResponseEntity<ApiResponse<PageResponse<PaymentResponse>>> getMyPayments(
            @CurrentUser UserPrincipal userPrincipal,
            @RequestParam(required = false) PaymentStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        log.info("Fetching payments for userId={} status={} page={} size={}",
                userPrincipal.getId(), status, pageable.getPageNumber(), pageable.getPageSize());
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
            @CurrentUser UserPrincipal userPrincipal, @jakarta.validation.Valid @RequestBody com.landgo.paymentservice.dto.request.PaymentSheetRequest request) {
        try {
            String customerId = stripeService.getOrCreateCustomer(userPrincipal);

            long amountCent = request.getAmountCent();
            String currency = request.getCurrency().toLowerCase();
            String description = request.getDescription() != null ? request.getDescription() : "LandGo Service Payment";

            PaymentIntent intent = stripeService.createPaymentIntent(customerId, amountCent, currency, description);

            return ResponseEntity.ok(ApiResponse.success(java.util.Map.of(
                    "paymentIntent", intent.getClientSecret(),
                    "customer", customerId)));
        } catch (Exception e) {
            log.error("Error creating payment intent", e);
            return ResponseEntity.status(500).body(ApiResponse.error(
                    "Failed to generate payment intent: " + e.getMessage(), "STRIPE_ERROR"));
        }
    }

    @PostMapping("/payment/verify-and-fulfill")
    @Operation(summary = "Verify Stripe PaymentIntent and activate subscription")
    public ResponseEntity<ApiResponse<Void>> verifyAndFulfill(
            @CurrentUser UserPrincipal userPrincipal, @RequestBody java.util.Map<String, Object> request) {
        if (request == null || !request.containsKey("paymentIntentId")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("paymentIntentId is required", "VALIDATION_ERROR"));
        }
        String paymentIntentId = request.get("paymentIntentId").toString();
        try {
            com.stripe.model.PaymentIntent intent = com.stripe.model.PaymentIntent.retrieve(paymentIntentId);
            if (!"succeeded".equals(intent.getStatus())) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Payment has not succeeded. Status: " + intent.getStatus(), "PAYMENT_NOT_SUCCEEDED"));
            }
            // Mark the corresponding internal payment record as SUCCESS
            paymentService.markPaymentSucceeded(userPrincipal, paymentIntentId);
            log.info("Payment verified and fulfilled for userId={} paymentIntentId={}", userPrincipal.getId(), paymentIntentId);
            return ResponseEntity.ok(ApiResponse.success("Payment verified and fulfilled", null));
        } catch (com.stripe.exception.StripeException e) {
            log.error("Stripe error verifying payment intent {}: {}", paymentIntentId, e.getMessage());
            return ResponseEntity.status(500).body(ApiResponse.error(
                    "Failed to verify payment: " + e.getMessage(), "STRIPE_ERROR"));
        }
    }
}
