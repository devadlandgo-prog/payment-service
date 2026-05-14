package com.landgo.paymentservice.service;

import com.landgo.paymentservice.entity.BillingProfile;
import com.landgo.paymentservice.repository.BillingProfileRepository;
import com.landgo.paymentservice.security.UserPrincipal;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.SubscriptionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeService {

    private final BillingProfileRepository billingProfileRepository;
    
    @org.springframework.beans.factory.annotation.Value("${app.stripe.publishable-key}")
    private String publishableKey;

    /**
     * Retrieves an existing Stripe Customer or creates a new one for the user.
     */
    public String getOrCreateCustomer(UserPrincipal userPrincipal) throws StripeException {
        return getOrCreateCustomer(userPrincipal.getId(), userPrincipal.getEmail());
    }

    public String getOrCreateCustomer(UUID userId, String email) throws StripeException {
        Optional<BillingProfile> profileOpt = billingProfileRepository.findByUserId(userId);

        if (profileOpt.isPresent()) {
            return profileOpt.get().getStripeCustomerId();
        }

        String stripeEmail = (email != null && !email.isBlank()) ? email : "user_" + userId + "@example.com";
        
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(stripeEmail)
                .setName("User " + userId)
                .putMetadata("userId", userId.toString())
                .build();

        Customer customer = Customer.create(params);
        
        BillingProfile newProfile = BillingProfile.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .stripeCustomerId(customer.getId())
                .build();
        billingProfileRepository.save(newProfile);

        return customer.getId();
    }

    /**
     * Generates an ephemeral key for the Stripe customer.
     */
    public String getEphemeralKey(String customerId) throws StripeException {
        com.stripe.param.EphemeralKeyCreateParams params = com.stripe.param.EphemeralKeyCreateParams.builder()
                .setCustomer(customerId)
                .setStripeVersion("2024-04-10") // Should match the SDK version
                .build();

        com.stripe.model.EphemeralKey key = com.stripe.model.EphemeralKey.create(params);
        return key.getSecret();
    }

    public String getPublishableKey() {
        return publishableKey;
    }

    /**
     * Creates a Stripe PaymentIntent for one-off payments (e.g., PaymentSheet).
     */
    public PaymentIntent createPaymentIntent(String customerId, long amountCent, String currency, String description) throws StripeException {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountCent)
                .setCurrency(currency)
                .setCustomer(customerId)
                .setDescription(description)
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build()
                )
                .build();

        return PaymentIntent.create(params);
    }

    /**
     * Subscribes a customer to a specific Price ID.
     */
    public Subscription createSubscription(String customerId, String priceId) throws StripeException {
        SubscriptionCreateParams params = SubscriptionCreateParams.builder()
                .setCustomer(customerId)
                .addItem(
                        SubscriptionCreateParams.Item.builder()
                                .setPrice(priceId)
                                .build()
                )
                // Wait for up to 23 hours for payment to succeed, depending on configuration
                .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
                .addAllExpand(java.util.List.of("latest_invoice.payment_intent"))
                .build();

        return Subscription.create(params);
    }

    /**
     * Updates an active Stripe subscription (e.g., plan change).
     */
    public Subscription updateSubscription(String stripeSubscriptionId, String newPriceId) throws StripeException {
        Subscription subscription = Subscription.retrieve(stripeSubscriptionId);
        com.stripe.param.SubscriptionUpdateParams params = com.stripe.param.SubscriptionUpdateParams.builder()
                .addItem(com.stripe.param.SubscriptionUpdateParams.Item.builder()
                        .setId(subscription.getItems().getData().get(0).getId())
                        .setPrice(newPriceId)
                        .build())
                .setPaymentBehavior(com.stripe.param.SubscriptionUpdateParams.PaymentBehavior.ALLOW_INCOMPLETE)
                .build();
        return subscription.update(params);
    }

    /**
     * Cancels an active Stripe subscription.
     */
    public Subscription cancelSubscription(String stripeSubscriptionId) throws StripeException {
        Subscription subscription = Subscription.retrieve(stripeSubscriptionId);
        return subscription.cancel();
    }
}
