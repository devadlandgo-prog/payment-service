package com.landgo.paymentservice.service;

import com.landgo.paymentservice.entity.Subscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional billing email.
 *
 * <p>Templates are rendered here and handed to user-service, which owns delivery. Every send
 * carries an idempotency key derived from the committed business event, so a Stripe webhook
 * replay or a retried {@code verify-and-fulfill} cannot mail the same receipt twice.
 *
 * <p>Nothing here throws: a mail failure must never roll back a payment that has already been
 * taken. Failures are logged for operations to pick up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEmailService {

    private final RestTemplate restTemplate;

    @Value("${app.services.user-service-url:http://localhost:8081}")
    private String userServiceUrl;

    @Value("${app.mail.logo-url:https://landgo.app/logo_with_tagline.png}")
    private String logoUrl;

    @Value("${app.mail.manage-subscription-url:https://landgo.ca/account/subscription}")
    private String manageSubscriptionUrl;

    @Value("${app.mail.billing-history-url:https://landgo.ca/account/billing}")
    private String billingHistoryUrl;

    @Value("${app.mail.payment-methods-url:https://landgo.ca/account/payment-methods}")
    private String paymentMethodsUrl;

    /** A land purchase receipt: states the credits added and the resulting balance. */
    public void sendLandPurchaseReceipt(UUID userId, String planName, BigDecimal amount, String currency,
                                        String paymentReference, int creditsAdded, int creditsAvailable,
                                        String idempotencyKey) {
        Map<String, String> user = fetchUser(userId);
        if (user == null) return;

        Map<String, String> vars = baseVars(user);
        vars.put("planName", planName);
        vars.put("productType", "Land Listing Credits");
        vars.put("amountPaid", formatMoney(amount, currency));
        vars.put("amount", formatMoney(amount, currency));
        vars.put("currency", currency);
        vars.put("txnId", paymentReference != null ? paymentReference : "N/A");
        vars.put("paymentId", paymentReference != null ? paymentReference : "N/A");
        vars.put("date", LocalDate.now().toString());
        vars.put("creditsAdded", String.valueOf(creditsAdded));
        vars.put("creditsAvailable", String.valueOf(creditsAvailable));
        // Credits do not renew. Anywhere the shared receipt template asks for a renewal, say so
        // plainly instead of printing a date that would be a lie.
        vars.put("renewalDate", "Never — listing credits do not expire");
        vars.put("renewalAmount", "—");
        vars.put("receiptUrl", billingHistoryUrl);

        send(user.get("email"), "LandGo — Payment receipt for your listing credits",
                "PaymentSuccess", vars, idempotencyKey);
    }

    /** A recurring-plan receipt. Paired with {@link #sendSubscriptionActivated} on first payment. */
    public void sendSubscriptionReceipt(UUID userId, Subscription subscription, BigDecimal amount,
                                        String currency, String paymentReference, String idempotencyKey) {
        Map<String, String> user = fetchUser(userId);
        if (user == null) return;

        Map<String, String> vars = subscriptionVars(user, subscription, amount, currency);
        vars.put("txnId", paymentReference != null ? paymentReference : "N/A");
        vars.put("paymentId", paymentReference != null ? paymentReference : "N/A");
        vars.put("date", LocalDate.now().toString());
        vars.put("receiptUrl", billingHistoryUrl);

        send(user.get("email"), "LandGo — Payment receipt", "PaymentSuccess", vars, idempotencyKey);
    }

    /**
     * Activation notice for a market-profession subscription.
     *
     * <p>Never sent for land credits: a credit purchase is not a subscription and gets the receipt
     * alone.
     */
    public void sendSubscriptionActivated(UUID userId, Subscription subscription, BigDecimal amount,
                                          String currency, String idempotencyKey) {
        if (SubscriptionService.isLandListing(subscription.getPlanCategory())) {
            log.debug("Skipping SubscriptionActivated for land credit purchase {}", subscription.getId());
            return;
        }
        Map<String, String> user = fetchUser(userId);
        if (user == null) return;

        Map<String, String> vars = subscriptionVars(user, subscription, amount, currency);
        vars.put("manageUrl", manageSubscriptionUrl);
        send(user.get("email"), "LandGo — Subscription activated", "SubscriptionActivated", vars, idempotencyKey);
    }

    public void sendSubscriptionCancelled(UUID userId, Subscription subscription, String idempotencyKey) {
        if (SubscriptionService.isLandListing(subscription.getPlanCategory())) {
            return;
        }
        Map<String, String> user = fetchUser(userId);
        if (user == null) return;

        Map<String, String> vars = subscriptionVars(user, subscription, subscription.getAmount(), null);
        vars.put("endDate", subscription.getEndDate() != null
                ? subscription.getEndDate().toLocalDate().toString() : "the end of your billing period");
        vars.put("accessEndDate", vars.get("endDate"));
        vars.put("reactivateUrl", manageSubscriptionUrl);
        send(user.get("email"), "LandGo — Subscription cancelled", "SubscriptionCanceled", vars, idempotencyKey);
    }

    /**
     * Confirms a completed plan switch.
     *
     * <p>Sent only once the new plan is active. The switch flow cancels the old plan as an
     * intermediate step, and that cancellation's ordinary email is suppressed — telling a
     * subscriber their plan was cancelled seconds before telling them it changed is alarming and
     * wrong.
     */
    public void sendPlanSwitched(UUID userId, Subscription subscription, String previousPlan,
                                 String idempotencyKey) {
        Map<String, String> user = fetchUser(userId);
        if (user == null) return;

        Map<String, String> vars = subscriptionVars(user, subscription, subscription.getAmount(), null);
        vars.put("previousPlan", previousPlan != null ? previousPlan : "your previous plan");
        vars.put("oldPlan", vars.get("previousPlan"));
        vars.put("newPlan", subscription.getPlan() != null ? subscription.getPlan() : "your new plan");
        vars.put("effectiveDate", LocalDate.now().toString());

        send(user.get("email"), "LandGo — Your plan has changed", "SubscriptionActivated", vars,
                idempotencyKey);
    }

    public void sendPaymentFailed(UUID userId, Subscription subscription, BigDecimal amount, String currency,
                                  String idempotencyKey) {
        Map<String, String> user = fetchUser(userId);
        if (user == null) return;

        Map<String, String> vars = subscriptionVars(user, subscription, amount, currency);
        vars.put("amountDue", formatMoney(amount, currency));
        vars.put("date", LocalDate.now().toString());
        vars.put("retryUrl", paymentMethodsUrl);
        vars.put("updatePaymentUrl", paymentMethodsUrl);
        send(user.get("email"), "ACTION REQUIRED: LandGo payment failed", "PaymentRejected", vars, idempotencyKey);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private Map<String, String> subscriptionVars(Map<String, String> user, Subscription subscription,
                                                 BigDecimal amount, String currency) {
        Map<String, String> vars = baseVars(user);
        vars.put("planName", subscription.getPlan() != null ? subscription.getPlan() : "LandGo plan");
        vars.put("productType", subscription.getPlanCategory() != null
                ? subscription.getPlanCategory() : "subscription");
        vars.put("amountPaid", formatMoney(amount, currency));
        vars.put("amount", formatMoney(amount, currency));
        vars.put("billingCycle", billingCycleLabel(subscription));
        String renewal = subscription.getEndDate() != null
                ? subscription.getEndDate().toLocalDate().toString() : "—";
        vars.put("renewalDate", renewal);
        vars.put("nextBillingDate", subscription.isAutoRenew() ? renewal : "—");
        vars.put("paidThroughDate", renewal);
        vars.put("renewalAmount", formatMoney(amount, currency));
        vars.put("manageUrl", manageSubscriptionUrl);
        return vars;
    }

    private String billingCycleLabel(Subscription subscription) {
        if (SubscriptionService.isLandListing(subscription.getPlanCategory())) {
            return "One-time purchase";
        }
        return subscription.getEndDate() != null && subscription.getStartDate() != null
                && subscription.getStartDate().plusDays(90).isBefore(subscription.getEndDate())
                ? "Annual" : "Monthly";
    }

    private Map<String, String> baseVars(Map<String, String> user) {
        Map<String, String> vars = new HashMap<>();
        vars.put("User", user.getOrDefault("fullName", "there"));
        vars.put("userName", user.getOrDefault("fullName", "there"));
        vars.put("logoUrl", logoUrl);
        return vars;
    }

    private String formatMoney(BigDecimal amount, String currency) {
        if (amount == null) return "—";
        String value = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return currency != null && !currency.isBlank()
                ? "$" + value + " " + currency.toUpperCase()
                : "$" + value;
    }

    private Map<String, String> fetchUser(UUID userId) {
        try {
            Map<?, ?> user = restTemplate.getForObject(userServiceUrl + "/internal/users/" + userId, Map.class);
            if (user == null || user.get("email") == null) {
                log.warn("No email on file for user {} — skipping billing email", userId);
                return null;
            }
            Map<String, String> info = new HashMap<>();
            info.put("email", String.valueOf(user.get("email")));
            Object name = user.get("fullName");
            info.put("fullName", name != null ? String.valueOf(name) : "there");
            return info;
        } catch (Exception e) {
            log.error("Failed to resolve recipient for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    private void send(String toEmail, String subject, String templateName, Map<String, String> vars,
                      String idempotencyKey) {
        try {
            String htmlBody = renderTemplate(templateName, vars);
            Map<String, Object> payload = new HashMap<>();
            payload.put("toEmail", toEmail);
            payload.put("subject", subject);
            payload.put("htmlBody", htmlBody);
            payload.put("templateName", templateName);
            payload.put("idempotencyKey", idempotencyKey);
            restTemplate.postForObject(userServiceUrl + "/internal/users/email/send", payload, Void.class);
            log.info("Queued {} email to user (key={})", templateName, idempotencyKey);
        } catch (Exception e) {
            // Deliberately swallowed: the payment has already been taken and committed.
            log.error("Failed to queue {} email (key={}): {}", templateName, idempotencyKey, e.getMessage());
        }
    }

    private String renderTemplate(String templateName, Map<String, String> variables) throws java.io.IOException {
        String templatePath = "email-templates/" + templateName + ".html";
        org.springframework.core.io.ClassPathResource resource =
                new org.springframework.core.io.ClassPathResource(templatePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Template file not found: " + templatePath);
        }
        String template = new String(resource.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        template = template.replace("/static/icon.svg", logoUrl);
        template = template.replace("{{logoUrl}}", logoUrl);

        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String value = entry.getValue() != null ? entry.getValue() : "";
                template = template.replace("<!-- -->" + entry.getKey() + "<!-- -->", value);
                template = template.replace("{{" + entry.getKey() + "}}", value);
                template = template.replace("${" + entry.getKey() + "}", value);
            }
        }
        return template;
    }
}
