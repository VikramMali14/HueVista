package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.dto.ProjectOrderResponse;
import com.gridstore.huevista.billing.dto.ProjectReopenResponse;
import com.gridstore.huevista.billing.dto.VerifyProjectPurchaseRequest;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.ProjectCredit;
import com.gridstore.huevista.billing.model.ProjectPurchase;
import com.gridstore.huevista.billing.repository.ProjectPurchaseRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buying one extra project with money: create a Razorpay order at the buyer's plan rate,
 * then verify the payment and credit the project.
 *
 * The cash rail exists beside the points one because not every shop wants to hold a
 * points balance to buy a single project — a counter that has run out mid-month should be
 * able to pay ₹65 and carry on. Cash is deliberately the dearer of the two on every tier
 * (₹99 against 80 points with no plan); points are bought in bulk or earned at the kiosk,
 * and that discount is what makes either of those worth doing.
 *
 * <p>The buyer names nothing. The PLAN sets the amount server-side at order time, and
 * verification re-reads the order back from Razorpay rather than trusting the reply — a
 * client that could name its own amount could pay the Business rate on no plan at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectPurchaseService {

    private final RazorpayClient razorpayClient;
    private final ProjectPurchaseRepository purchaseRepository;
    private final ProjectCreditService projectCreditService;
    private final PricingService pricingService;
    private final BillingEmailService billingEmailService;

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    private static final String ORDER_PURPOSE = "project_purchase";
    private static final String REOPEN_PURPOSE = "project_reopen";

    /** Create the Razorpay order the client opens in Checkout. */
    public ProjectOrderResponse createOrder(String userId) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("Online payment is not configured.");
        }
        Plan pricedAs = pricingService.pricingPlanFor(userId);
        int amountPaise = pricedAs.extraProjectPriceWithTaxInPaise();
        try {
            JSONObject req = new JSONObject();
            req.put("amount", amountPaise);
            req.put("currency", pricingService.currency());
            req.put("receipt", "project_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("purpose", ORDER_PURPOSE);
            // The tier travels on the order so verify can check the amount against the
            // rate this order was actually opened at. Re-reading the buyer's plan at
            // verification time instead would let a shop open an order on Business, let
            // the plan lapse, and still be checked against the cheap rate it paid.
            notes.put("plan", pricedAs.name());
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Project order created: user={} order={} plan={} amountPaise={}",
                    userId, orderId, pricedAs, amountPaise);

            return ProjectOrderResponse.builder()
                    .orderId(orderId)
                    .pricingPlan(pricedAs.name())
                    .amount(amountPaise)
                    .currency(pricingService.currency())
                    .razorpayKeyId(keyId)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay project order creation failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
    }

    /**
     * Verify the Checkout signature and credit the project the ORDER was for.
     *
     * Signature first, then the order itself: a valid signature proves the payment belongs
     * to some order on this merchant account, not that it belongs to this one, this user,
     * or this price. All three are read back from Razorpay rather than trusted.
     */
    @Transactional
    public void verifyAndCredit(String userId, VerifyProjectPurchaseRequest req) {
        verifySignature(req);

        Plan pricedAs;
        int amountPaise;
        try {
            Order order = razorpayClient.orders.fetch(req.getOrderId());
            amountPaise = ((Number) order.get("amount")).intValue();
            JSONObject notes = order.get("notes");
            String purpose = notes != null ? notes.optString("purpose", "") : "";
            String orderUserId = notes != null ? notes.optString("userId", "") : "";
            String planName = notes != null ? notes.optString("plan", "") : "";
            pricedAs = parsePlan(planName);

            // The amount must still be what that tier charges. Without it, an order whose
            // notes were somehow crafted could claim a project for a rupee.
            if (!ORDER_PURPOSE.equals(purpose) || !userId.equals(orderUserId) || pricedAs == null
                    || amountPaise != pricedAs.extraProjectPriceWithTaxInPaise()) {
                log.warn("Project order mismatch: user={} order={} amount={} purpose={} orderUser={} plan={}",
                        userId, req.getOrderId(), amountPaise, purpose, orderUserId, planName);
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during project verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        claimPayment(req, userId, pricedAs, amountPaise);
        projectCreditService.creditPurchasedProject(userId, ProjectCredit.Source.PURCHASE);
        log.info("Project bought with money: user={} plan={} amountPaise={}",
                userId, pricedAs, amountPaise);
        billingEmailService.sendProjectPurchased(userId, amountPaise, pricingService.projectValidDays());
    }

    // ── Reopen: another validity window on a project already paid for once ──────

    /**
     * Create the Razorpay order for a paid reopen.
     *
     * The access check happens HERE, before the buyer is ever shown a payment sheet —
     * charging first and discovering afterwards that the project was never locked is the
     * failure this order is built to avoid. The project id travels on the order so verify
     * extends the project that was actually paid for rather than one the client names
     * afterwards.
     */
    public ProjectOrderResponse createReopenOrder(String userId, String projectId) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("Online payment is not configured.");
        }
        projectCreditService.requireReopenable(userId, projectId);
        int amountPaise = pricingService.reopenPricePaise();
        try {
            JSONObject req = new JSONObject();
            req.put("amount", amountPaise);
            req.put("currency", pricingService.currency());
            req.put("receipt", "reopen_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("purpose", REOPEN_PURPOSE);
            notes.put("projectId", projectId);
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Reopen order created: user={} order={} project={} amountPaise={}",
                    userId, orderId, projectId, amountPaise);

            return ProjectOrderResponse.builder()
                    .orderId(orderId)
                    .pricingPlan(pricingService.pricingPlanFor(userId).name())
                    .amount(amountPaise)
                    .currency(pricingService.currency())
                    .razorpayKeyId(keyId)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay reopen order creation failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
    }

    /**
     * Verify the Checkout signature and extend the project the ORDER was for.
     *
     * Which project is read back from the order's notes, not from the request: a client
     * that could name the project could pay ₹10 for one and reopen another.
     */
    @Transactional
    public ProjectReopenResponse verifyAndCreditReopen(String userId, VerifyProjectPurchaseRequest req) {
        verifySignature(req);

        String projectId;
        int amountPaise;
        try {
            Order order = razorpayClient.orders.fetch(req.getOrderId());
            amountPaise = ((Number) order.get("amount")).intValue();
            JSONObject notes = order.get("notes");
            String purpose = notes != null ? notes.optString("purpose", "") : "";
            String orderUserId = notes != null ? notes.optString("userId", "") : "";
            projectId = notes != null ? notes.optString("projectId", "") : "";

            if (!REOPEN_PURPOSE.equals(purpose) || !userId.equals(orderUserId)
                    || projectId.isBlank() || amountPaise != pricingService.reopenPricePaise()) {
                log.warn("Reopen order mismatch: user={} order={} amount={} purpose={} orderUser={} project={}",
                        userId, req.getOrderId(), amountPaise, purpose, orderUserId, projectId);
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during reopen verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        claimPayment(req, userId, null, amountPaise);
        ProjectReopenResponse reopened = projectCreditService.creditReopen(userId, projectId, amountPaise);
        billingEmailService.sendProjectReopened(userId, 0, amountPaise, reopened.getDaysAdded());
        return reopened;
    }

    /** The Checkout signature must belong to this merchant account. */
    private void verifySignature(VerifyProjectPurchaseRequest req) {
        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", req.getOrderId());
            options.put("razorpay_payment_id", req.getPaymentId());
            options.put("razorpay_signature", req.getSignature());
            if (!Utils.verifyPaymentSignature(options, keySecret)) {
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay signature verification error: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }
    }

    /**
     * Claim the payment exactly once. The pre-check keeps the common case readable; the
     * unique constraint is the race-safe backstop for two concurrent submits.
     */
    private void claimPayment(VerifyProjectPurchaseRequest req, String userId,
                              Plan pricedAs, int amountPaise) {
        if (purchaseRepository.existsByPaymentId(req.getPaymentId())) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
        try {
            purchaseRepository.saveAndFlush(ProjectPurchase.of(
                    req.getPaymentId(), req.getOrderId(), userId, pricedAs, amountPaise));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
    }

    /** The tier named on an order, or null when it names none we recognise. */
    private Plan parsePlan(String name) {
        try {
            return name == null || name.isBlank() ? null : Plan.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
