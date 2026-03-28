package com.landgo.paymentservice.controller;

import com.landgo.paymentservice.dto.request.ChangeSubscriptionRequest;
import com.landgo.paymentservice.dto.request.ProfessionalSubscribeRequest;
import com.landgo.paymentservice.dto.request.SubscriptionRequest;
import com.landgo.paymentservice.dto.response.ApiResponse;
import com.landgo.paymentservice.dto.response.SubscriptionPlanResponse;
import com.landgo.paymentservice.dto.response.SubscriptionResponse;
import com.landgo.paymentservice.security.CurrentUser;
import com.landgo.paymentservice.security.UserPrincipal;
import com.landgo.paymentservice.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscription", description = "Subscription management APIs")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @PostMapping
    @Operation(summary = "Subscribe to a plan")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> subscribe(
            @CurrentUser UserPrincipal userPrincipal, @Valid @RequestBody SubscriptionRequest request) {
        SubscriptionResponse response = subscriptionService.subscribe(userPrincipal, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Subscription successful", response));
    }

    @GetMapping("/current")
    @Operation(summary = "Get current subscription")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getCurrentSubscription(@CurrentUser UserPrincipal userPrincipal) {
        SubscriptionResponse response = subscriptionService.getCurrentSubscription(userPrincipal);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel subscription")
    public ResponseEntity<ApiResponse<Void>> cancelSubscription(
            @CurrentUser UserPrincipal userPrincipal, @RequestParam(required = false) String reason) {
        subscriptionService.cancelSubscription(userPrincipal, reason);
        return ResponseEntity.ok(ApiResponse.success("Subscription cancelled successfully", null));
    }

    @PostMapping("/change")
    @Operation(summary = "Change subscription plan")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> changeSubscription(
            @CurrentUser UserPrincipal userPrincipal, @Valid @RequestBody ChangeSubscriptionRequest request) {
        SubscriptionResponse response = subscriptionService.changePlan(userPrincipal, request);
        return ResponseEntity.ok(ApiResponse.success("Subscription plan changed successfully", response));
    }

    @GetMapping("/plans")
    @Operation(summary = "Get available subscription plans")
    public ResponseEntity<ApiResponse<List<SubscriptionPlanResponse>>> getPlans() {
        return ResponseEntity.ok(ApiResponse.success(subscriptionService.getSubscriptionPlans()));
    }

    @PostMapping("/intent")
    @Operation(summary = "Create subscription payment intent")
    public ResponseEntity<ApiResponse<Map<String, String>>> createIntent(
            @CurrentUser UserPrincipal userPrincipal, @Valid @RequestBody ProfessionalSubscribeRequest request) {
        Map<String, String> intent = subscriptionService.createSubscriptionIntent(userPrincipal, request);
        return ResponseEntity.ok(ApiResponse.success("Payment intent created", intent));
    }
}
