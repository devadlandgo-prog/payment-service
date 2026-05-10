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

    /**
     * Retrieves an existing Stripe Customer or creates a new one for the user.
     */
    public String getOrCreateCustomer(UserPrincipal userPrincipal) throws StripeException {
        UUID userId = userPrincipal.getId();
        Optional<BillingProfile> profileOpt = billingProfileRepository.findByUserId(userId);

        if (profileOpt.isPresent()) {
            return profileOpt.get().getStripeCustomerId();
        }

        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail("user_" + userId + "@example.com") // Dummy email since UserPrincipal doesn't store it
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
     * Cancels an active Stripe subscription.
     */
    public Subscription cancelSubscription(String stripeSubscriptionId) throws StripeException {
        Subscription subscription = Subscription.retrieve(stripeSubscriptionId);
        return subscription.cancel();
    }
}
