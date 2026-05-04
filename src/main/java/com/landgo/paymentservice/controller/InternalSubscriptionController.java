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
    public ResponseEntity<Map<String, Boolean>> hasActiveSubscription(@PathVariable UUID userId) {
        boolean active = subscriptionService.hasActiveSubscription(userId);
        return ResponseEntity.ok(Map.of("active", active));
    }

    @PostMapping("/user/{userId}/intent")
    public ResponseEntity<Map<String, String>> createSubscriptionIntent(
            @PathVariable UUID userId,
            @RequestBody ProfessionalSubscribeRequest request) {
        return ResponseEntity.ok(subscriptionService.createSubscriptionIntent(userId, request));
    }
}
