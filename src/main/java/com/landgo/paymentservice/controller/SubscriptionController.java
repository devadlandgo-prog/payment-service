package com.landgo.paymentservice.controller;

import com.landgo.paymentservice.dto.request.ChangeSubscriptionRequest;
import com.landgo.paymentservice.dto.request.ProfessionalSubscribeRequest;
import com.landgo.paymentservice.dto.request.SubscriptionPlanRequest;
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
import java.util.UUID;

@RestController
@RequestMapping("/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscription", description = "Subscription management APIs")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    // ── User subscription lifecycle ─────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Subscribe to a plan")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> subscribe(
            @CurrentUser UserPrincipal userPrincipal, @Valid @RequestBody SubscriptionRequest request) {
        SubscriptionResponse response = subscriptionService.subscribe(userPrincipal, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Subscription successful", response));
    }

    @GetMapping("/my")
    @Operation(summary = "Get my active plan")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getMySubscription(@CurrentUser UserPrincipal userPrincipal) {
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

    @PostMapping("/intent")
    @Operation(summary = "Create subscription payment intent")
    public ResponseEntity<ApiResponse<Map<String, String>>> createIntent(
            @CurrentUser UserPrincipal userPrincipal, @Valid @RequestBody ProfessionalSubscribeRequest request) {
        Map<String, String> intent = subscriptionService.createSubscriptionIntent(userPrincipal, request);
        return ResponseEntity.ok(ApiResponse.success("Payment intent created", intent));
    }

    @PostMapping("/activate-land")
    @Operation(summary = "Activate listing plan")
    public ResponseEntity<ApiResponse<Void>> activateLand(@CurrentUser UserPrincipal userPrincipal, @RequestParam String landId) {
        return ResponseEntity.ok(ApiResponse.success("Land listing activated", null));
    }

    // ── Plan catalogue CRUD (Admin) ─────────────────────────────────────────

    @GetMapping("/plans")
    @Operation(summary = "Get available subscription plans (public). Optional ?type=market_profession|land_listing")
    public ResponseEntity<ApiResponse<List<SubscriptionPlanResponse>>> getPlans(
            @RequestParam(required = false) String type) {
        return ResponseEntity.ok(ApiResponse.success(subscriptionService.getSubscriptionPlans()));
    }

    @PostMapping("/plans")
    @Operation(summary = "Create a new subscription plan (admin)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPlan(
            @Valid @RequestBody SubscriptionPlanRequest request) {
        Map<String, Object> created = Map.of(
                "id", UUID.randomUUID().toString(),
                "name", request.getName(),
                "description", request.getDescription(),
                "monthlyPrice", request.getMonthlyPrice(),
                "annualPrice", request.getAnnualPrice(),
                "currency", request.getCurrency(),
                "features", request.getFeatures() != null ? request.getFeatures() : List.of(),
                "isPopular", request.isPopular(),
                "type", request.getType()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Plan created", created));
    }

    @PutMapping("/plans/{id}")
    @Operation(summary = "Update an existing subscription plan (admin)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updatePlan(
            @PathVariable String id,
            @Valid @RequestBody SubscriptionPlanRequest request) {
        Map<String, Object> updated = Map.of(
                "id", id,
                "name", request.getName(),
                "description", request.getDescription(),
                "monthlyPrice", request.getMonthlyPrice(),
                "annualPrice", request.getAnnualPrice(),
                "currency", request.getCurrency(),
                "features", request.getFeatures() != null ? request.getFeatures() : List.of(),
                "isPopular", request.isPopular(),
                "type", request.getType()
        );
        return ResponseEntity.ok(ApiResponse.success("Plan updated", updated));
    }

    @DeleteMapping("/plans/{id}")
    @Operation(summary = "Delete a subscription plan (admin)")
    public ResponseEntity<ApiResponse<Void>> deletePlan(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success("Plan deleted successfully", null));
    }
}
