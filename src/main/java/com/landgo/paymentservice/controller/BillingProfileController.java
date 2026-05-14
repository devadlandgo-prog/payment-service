package com.landgo.paymentservice.controller;

import com.landgo.paymentservice.dto.response.ApiResponse;
import com.landgo.paymentservice.dto.response.BillingProfileResponse;
import com.landgo.paymentservice.service.BillingProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/billing-profiles")
@RequiredArgsConstructor
@Tag(name = "Billing Profile", description = "Billing profile management APIs")
public class BillingProfileController {

    private final BillingProfileService billingProfileService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get all billing profiles (admin only)")
    public ResponseEntity<ApiResponse<List<BillingProfileResponse>>> getAllProfiles() {
        return ResponseEntity.ok(ApiResponse.success(billingProfileService.getAllProfiles()));
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get billing profile by user ID (admin only)")
    public ResponseEntity<ApiResponse<BillingProfileResponse>> getProfileByUserId(@PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.success(billingProfileService.getProfileByUserId(userId)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete billing profile (admin only)")
    public ResponseEntity<ApiResponse<Void>> deleteProfile(@PathVariable UUID id) {
        billingProfileService.deleteProfile(id);
        return ResponseEntity.ok(ApiResponse.success("Billing profile deleted successfully", null));
    }
}
