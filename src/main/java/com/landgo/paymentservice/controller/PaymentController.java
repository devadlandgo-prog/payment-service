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
import org.springframework.security.access.prepost.PreAuthorize;
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        log.info("Fetching payments for userId={} status={} page={} size={}",
                userPrincipal.getId(), status, page, size);
        PageResponse<PaymentResponse> response = paymentService.getMyPayments(userPrincipal, status, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/transactions")
    @Operation(summary = "Get my transactions")
    public ResponseEntity<ApiResponse<PageResponse<PaymentResponse>>> getMyTransactions(
            @CurrentUser UserPrincipal userPrincipal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
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
            String ephemeralKey = stripeService.getEphemeralKey(customerId);

            return ResponseEntity.ok(ApiResponse.success(java.util.Map.of(
                    "paymentIntent", intent.getClientSecret(),
                    "customer", customerId,
                    "ephemeralKey", ephemeralKey,
                    "publishableKey", stripeService.getPublishableKey())));
        } catch (Exception e) {
            log.error("Error creating payment intent", e);
            return ResponseEntity.status(500).body(ApiResponse.error(
                    "Failed to generate payment intent: " + e.getMessage(), "STRIPE_ERROR"));
        }
    }

    @PostMapping({"/payment/verify-and-fulfill", "/verify-and-fulfill"})
    @Operation(summary = "Verify Stripe PaymentIntent and activate the matching subscription/category")
    public ResponseEntity<ApiResponse<com.landgo.paymentservice.dto.response.VerifyAndFulfillResponse>> verifyAndFulfill(
            @CurrentUser UserPrincipal userPrincipal, @jakarta.validation.Valid @RequestBody com.landgo.paymentservice.dto.request.VerifyAndFulfillRequest request) {
        String paymentIntentId = request.getPaymentIntentId();
        try {
            com.stripe.model.PaymentIntent intent = com.stripe.model.PaymentIntent.retrieve(paymentIntentId);
            if (!"succeeded".equals(intent.getStatus())) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Payment has not succeeded. Status: " + intent.getStatus(), "PAYMENT_NOT_SUCCEEDED"));
            }
            java.util.UUID subId = paymentService.markPaymentSucceeded(userPrincipal, paymentIntentId, request.getPlanCategory());
            log.info("Payment verified and fulfilled for userId={} paymentIntentId={}", userPrincipal.getId(), paymentIntentId);
            return ResponseEntity.ok(ApiResponse.success("Payment verified and fulfilled",
                    com.landgo.paymentservice.dto.response.VerifyAndFulfillResponse.builder()
                            .success(true)
                            .subscriptionId(subId)
                            .build()));
        } catch (com.stripe.exception.StripeException e) {
            log.error("Stripe error verifying payment intent {}: {}", paymentIntentId, e.getMessage());
            return ResponseEntity.status(500).body(ApiResponse.error(
                    "Failed to verify payment: " + e.getMessage(), "STRIPE_ERROR"));
        }
    }

    @PostMapping("/payment-methods/setup-intent")
    @Operation(summary = "Generate Stripe SetupIntent")
    public ResponseEntity<ApiResponse<java.util.Map<String, String>>> createSetupIntent(
            @CurrentUser UserPrincipal userPrincipal) {
        try {
            String customerId = stripeService.getOrCreateCustomer(userPrincipal);
            com.stripe.model.SetupIntent intent = stripeService.createSetupIntent(customerId);
            String ephemeralKey = stripeService.getEphemeralKey(customerId);
            return ResponseEntity.ok(ApiResponse.success(java.util.Map.of(
                    "setupIntent", intent.getClientSecret(),
                    "customer", customerId,
                    "ephemeralKey", ephemeralKey,
                    "publishableKey", stripeService.getPublishableKey())));
        } catch (Exception e) {
            log.error("Error creating setup intent", e);
            return ResponseEntity.status(500).body(ApiResponse.error(
                    "Failed to generate setup intent: " + e.getMessage(), "STRIPE_ERROR"));
        }
    }

    @PostMapping({"/payment-methods/setup", "/payment-methods/my"})
    @Operation(summary = "Attach payment method to customer and make it default")
    public ResponseEntity<ApiResponse<Void>> setupPaymentMethod(
            @CurrentUser UserPrincipal userPrincipal,
            @RequestBody java.util.Map<String, String> request) {
        if (request == null || !request.containsKey("paymentMethodId")) {
            return ResponseEntity.badRequest().body(ApiResponse.error("paymentMethodId is required", "VALIDATION_ERROR"));
        }
        String paymentMethodId = request.get("paymentMethodId");
        try {
            String customerId = stripeService.getOrCreateCustomer(userPrincipal);
            stripeService.attachPaymentMethod(customerId, paymentMethodId);
            return ResponseEntity.ok(ApiResponse.success("Payment method setup successful", null));
        } catch (Exception e) {
            log.error("Error attaching payment method", e);
            return ResponseEntity.status(500).body(ApiResponse.error(
                    "Failed to attach payment method: " + e.getMessage(), "STRIPE_ERROR"));
        }
    }

    @GetMapping({"/payment-methods", "/payment-methods/my"})
    @Operation(summary = "Get user's registered payment methods")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> getMyPaymentMethods(
            @CurrentUser UserPrincipal userPrincipal) {
        try {
            String customerId = stripeService.getOrCreateCustomer(userPrincipal);
            java.util.List<com.stripe.model.PaymentMethod> methods = stripeService.getPaymentMethods(customerId);
            java.util.List<java.util.Map<String, Object>> cardDetails = methods.stream()
                    .map(m -> {
                        if (m.getCard() != null) {
                            return java.util.Map.<String, Object>of(
                                    "id", m.getId(),
                                     "brand", m.getCard().getBrand(),
                                     "last4", m.getCard().getLast4()
                            );
                         }
                         return java.util.Map.<String, Object>of("id", m.getId(), "brand", "unknown", "last4", "");
                    })
                    .toList();
            return ResponseEntity.ok(ApiResponse.success(cardDetails));
        } catch (Exception e) {
            log.error("Error listing payment methods", e);
            return ResponseEntity.status(500).body(ApiResponse.error(
                    "Failed to list payment methods: " + e.getMessage(), "STRIPE_ERROR"));
        }
    }

    @GetMapping("/admin/transactions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get all transactions (Admin only)")
    public ResponseEntity<ApiResponse<PageResponse<PaymentResponse>>> getAllTransactions(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String provider,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        log.info("Admin fetching all transactions status={} provider={} page={} size={}",
                status, provider, page, size);

        PageResponse<PaymentResponse> response;
        if (status != null && provider != null) {
            response = paymentService.getAllPaymentsByStatusAndProvider(status, provider, pageable);
        } else if (status != null) {
            response = paymentService.getAllPaymentsByStatus(status, pageable);
        } else if (provider != null) {
            response = paymentService.getAllPaymentsByProvider(provider, pageable);
        } else {
            response = paymentService.getAllPayments(pageable);
        }

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
