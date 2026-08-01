package com.landgo.paymentservice.controller;

import com.landgo.paymentservice.service.SubscriptionService;
import com.stripe.exception.SignatureVerificationException;
import io.swagger.v3.oas.annotations.Hidden;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
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
@Hidden
@RestController
@RequestMapping("/payment/webhook")
@RequiredArgsConstructor
public class StripeWebhookController {

    @Value("${app.stripe.webhook-secret}")
    private String webhookSecret;

    private final SubscriptionService subscriptionService;

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

        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject = deserializer.getObject().orElse(null);
        if (stripeObject == null) {
            log.warn("Deserialization failed for event {} — possible API version mismatch", event.getType());
            return ResponseEntity.ok("Success (but deserialization failed)");
        }

        switch (event.getType()) {
            case "invoice.payment_succeeded" -> handleInvoicePaymentSucceeded(stripeObject);
            case "invoice.payment_failed" -> handleInvoicePaymentFailed(stripeObject);
            case "customer.subscription.deleted" -> handleSubscriptionDeleted(stripeObject);
            default -> log.info("Unhandled Stripe event type: {}", event.getType());
        }

        return ResponseEntity.ok("Success");
    }

    private void handleInvoicePaymentSucceeded(StripeObject stripeObject) {
        if (!(stripeObject instanceof Invoice invoice))
            return;

        String stripeSubscriptionId = invoice.getSubscription();
        String stripeCustomerId = invoice.getCustomer();
        Long amountPaid = invoice.getAmountPaid();
        String currency = invoice.getCurrency();
        String paymentIntentId = invoice.getPaymentIntent();

        log.info("Webhook invoice.payment_succeeded: subId={} amountPaid={}", stripeSubscriptionId, amountPaid);
        if (stripeSubscriptionId != null) {
            subscriptionService.handleInvoicePaymentSucceeded(stripeSubscriptionId, stripeCustomerId, amountPaid,
                    currency, paymentIntentId);
        }
    }

    private void handleInvoicePaymentFailed(StripeObject stripeObject) {
        if (!(stripeObject instanceof Invoice invoice))
            return;

        String stripeSubscriptionId = invoice.getSubscription();
        Long amountDue = invoice.getAmountDue();
        String currency = invoice.getCurrency();
        String paymentIntentId = invoice.getPaymentIntent();

        log.warn("Webhook invoice.payment_failed: subId={} amountDue={}", stripeSubscriptionId, amountDue);
        if (stripeSubscriptionId != null) {
            subscriptionService.handleInvoicePaymentFailed(stripeSubscriptionId, amountDue, currency, paymentIntentId);
        }
    }

    private void handleSubscriptionDeleted(StripeObject stripeObject) {
        if (!(stripeObject instanceof com.stripe.model.Subscription stripeSub))
            return;

        String stripeSubscriptionId = stripeSub.getId();
        log.info("Webhook customer.subscription.deleted: subId={}", stripeSubscriptionId);
        if (stripeSubscriptionId != null) {
            subscriptionService.handleSubscriptionDeleted(stripeSubscriptionId);
        }
    }
}
