package com.landgo.paymentservice.controller;

import com.landgo.paymentservice.dto.response.ApiResponse;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/payment/webhook")
@RequiredArgsConstructor
public class StripeWebhookController {

    @Value("${app.stripe.webhook-secret}")
    private String webhookSecret;

    @PostMapping
    public ResponseEntity<String> handleStripeEvent(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        
        Event event;

        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.error("Stripe webhook signature verification failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        } catch (Exception e) {
            log.error("Error parsing Stripe webhook payload", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid payload");
        }

        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject = null;
        if (dataObjectDeserializer.getObject().isPresent()) {
            stripeObject = dataObjectDeserializer.getObject().get();
        } else {
            log.warn("Deserialization failed, probably due to an API version mismatch.");
        }

        switch (event.getType()) {
            case "invoice.payment_succeeded":
                log.info("Payment succeeded for invoice: {}", stripeObject);
                // Handle payment success (e.g., mark transaction as success)
                break;
            case "invoice.payment_failed":
                log.warn("Payment failed for invoice: {}", stripeObject);
                // Handle payment failure (e.g., mark transaction as failed, suspend subscription)
                break;
            case "customer.subscription.deleted":
                log.info("Subscription deleted: {}", stripeObject);
                // Handle subscription cancellation
                break;
            default:
                log.info("Unhandled event type: {}", event.getType());
                break;
        }

        return ResponseEntity.ok("Success");
    }
}
