package com.landgo.paymentservice.controller;

import com.landgo.paymentservice.dto.request.ProfessionalSubscribeRequest;
import com.landgo.paymentservice.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Hidden
@RestController
@RequestMapping("/internal/subscriptions")
@RequiredArgsConstructor
public class InternalSubscriptionController {

    private final SubscriptionService subscriptionService;

    @GetMapping("/user/{userId}/active")
    public ResponseEntity<Map<String, Boolean>> hasActiveSubscription(
            @PathVariable UUID userId,
            @RequestParam(required = false) String type) {
        boolean active = subscriptionService.hasActiveSubscription(userId, type);
        return ResponseEntity.ok(Map.of("active", active));
    }

    @GetMapping("/user/{userId}/plan")
    public ResponseEntity<Map<String, Object>> getUserPlan(
            @PathVariable UUID userId,
            @RequestParam(required = false) String category) {
        Map<String, Object> data = subscriptionService.getUserPlanDetails(userId, category);
        return ResponseEntity.ok(Map.of("data", data));
    }


    @PostMapping("/user/{userId}/intent")
    public ResponseEntity<com.landgo.paymentservice.dto.response.SubscriptionIntentResponse> createSubscriptionIntent(
            @PathVariable UUID userId,
            @RequestBody ProfessionalSubscribeRequest request) {
        return ResponseEntity.ok(subscriptionService.createSubscriptionIntent(userId, request.getEmail(), request));
    }
}
