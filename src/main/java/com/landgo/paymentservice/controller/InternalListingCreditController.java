package com.landgo.paymentservice.controller;

import com.landgo.paymentservice.dto.response.ListingCreditBalanceResponse;
import com.landgo.paymentservice.exception.ConflictException;
import com.landgo.paymentservice.service.ListingCreditService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Internal credit API — core-service calls this when a listing is posted or activated.
 *
 * <p>Not exposed to external clients; secured by network policy like the other internal routes.
 */
@Hidden
@Slf4j
@RestController
@RequestMapping("/internal/listing-credits")
@RequiredArgsConstructor
public class InternalListingCreditController {

    private final ListingCreditService listingCreditService;

    @GetMapping("/user/{userId}")
    public ResponseEntity<ListingCreditBalanceResponse> getBalance(@PathVariable UUID userId) {
        return ResponseEntity.ok(listingCreditService.getBalance(userId));
    }

    /**
     * Spends one credit.
     *
     * @return 200 with the new balance, or 409 when the user has none left — core-service turns
     *         that into the "buy a package" error the app shows
     */
    @PostMapping("/user/{userId}/consume")
    public ResponseEntity<?> consume(
            @PathVariable UUID userId,
            @RequestParam(required = false) UUID listingId,
            @RequestParam(required = false) String idempotencyKey) {
        try {
            return ResponseEntity.ok(listingCreditService.consumeCredit(userId, listingId, idempotencyKey));
        } catch (ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "message", e.getMessage(), "code", "NO_LISTING_CREDITS"));
        }
    }
}
