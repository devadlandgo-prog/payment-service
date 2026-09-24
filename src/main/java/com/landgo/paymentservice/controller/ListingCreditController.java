package com.landgo.paymentservice.controller;

import com.landgo.paymentservice.dto.request.CreditAdjustmentRequest;
import com.landgo.paymentservice.dto.response.ApiResponse;
import com.landgo.paymentservice.dto.response.ListingCreditBalanceResponse;
import com.landgo.paymentservice.dto.response.ListingCreditLedgerResponse;
import com.landgo.paymentservice.dto.response.PageResponse;
import com.landgo.paymentservice.security.CurrentUser;
import com.landgo.paymentservice.security.UserPrincipal;
import com.landgo.paymentservice.service.ListingCreditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Land-listing credit balance and history.
 *
 * <p>There is no cancel or expire action here by design: credits are bought outright and never
 * lapse. The only way a balance goes down is posting a listing or an audited admin correction.
 */
@RestController
@RequestMapping("/listing-credits")
@RequiredArgsConstructor
@Tag(name = "Listing Credits", description = "Land listing credit balance, history and admin adjustments")
public class ListingCreditController {

    private final ListingCreditService listingCreditService;

    @GetMapping("/my")
    @Operation(summary = "My listing credit balance")
    public ResponseEntity<ApiResponse<ListingCreditBalanceResponse>> myBalance(
            @CurrentUser UserPrincipal userPrincipal) {
        return ResponseEntity.ok(ApiResponse.success(listingCreditService.getBalance(userPrincipal.getId())));
    }

    @GetMapping("/my/history")
    @Operation(summary = "My listing credit purchase and usage history")
    public ResponseEntity<ApiResponse<PageResponse<ListingCreditLedgerResponse>>> myHistory(
            @CurrentUser UserPrincipal userPrincipal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.success(
                listingCreditService.getHistory(userPrincipal.getId(), pageable)));
    }

    @GetMapping("/admin/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "A member's aggregated credit balance (admin only)")
    public ResponseEntity<ApiResponse<ListingCreditBalanceResponse>> memberBalance(@PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.success(listingCreditService.getBalance(userId)));
    }

    @GetMapping("/admin/user/{userId}/history")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "A member's immutable credit ledger (admin only)")
    public ResponseEntity<ApiResponse<PageResponse<ListingCreditLedgerResponse>>> memberHistory(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                listingCreditService.getHistory(userId, PageRequest.of(page, size))));
    }

    @PostMapping("/admin/user/{userId}/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Apply an auditable credit adjustment or refund reversal (admin only)",
            description = "Positive credits grant, negative withdraw. Never drops the purchased total "
                    + "below what the member has already spent. The reason is recorded against the "
                    + "acting administrator.")
    public ResponseEntity<ApiResponse<ListingCreditBalanceResponse>> adjust(
            @CurrentUser UserPrincipal userPrincipal,
            @PathVariable UUID userId,
            @Valid @RequestBody CreditAdjustmentRequest request) {
        ListingCreditBalanceResponse balance = listingCreditService.adjust(
                userId, request.getCredits(), request.getReason(), userPrincipal.getId(),
                request.getIdempotencyKey());
        return ResponseEntity.ok(ApiResponse.success("Credit adjustment applied", balance));
    }
}
