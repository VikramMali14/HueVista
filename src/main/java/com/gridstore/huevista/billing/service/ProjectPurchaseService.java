package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.dto.ProjectOrderResponse;
import com.gridstore.huevista.billing.dto.ProjectReopenResponse;
import com.gridstore.huevista.billing.dto.VerifyProjectPurchaseRequest;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.PaymentFlow;
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
 * (₹199 against 80 points with no plan); points are bought in bulk or earned at the kiosk,
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
    private final PaymentAttemptService paymentAttemptService;

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    private static final String ORDER_PURPOSE = "project_purchase";
    private static final String REOPEN_PURPOSE = "project_reopen";
    private static final String RENDER_PURPOSE = "project_render";

    /** Create the Razorpay order the client opens in Checkout, for one project. */
    public ProjectOrderResponse createOrder(String userId) {
        return createOrder(userId, 1);
    }

    /**
     * Create the Razorpay order the client opens in Checkout.
     *
     * {@code credits} is the only thing the buyer chooses, and it is not a free number:
     * one project, or a whole bundle. Anything else is refused rather than priced, because
     * a quantity the server will multiply by a rate is exactly the input a client should
     * not be able to invent — "0 projects for ₹0" and "1000 projects at a rounding error"
     * both live in the gaps a free quantity leaves open.
     */
    public ProjectOrderResponse createOrder(String userId, int credits) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("Online payment is not configured.");
        }
        boolean bundle = requireKnownQuantity(credits);
        Plan pricedAs = pricingService.pricingPlanFor(userId);
        // Priced through the same helper verification uses, so the two can never drift.
        int amountPaise = expectedAmount(pricedAs, credits);
        String description = bundle ? PricingService.BUNDLE_CREDITS + " projects" : "1 extra project";
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
            // So does the quantity, for the same reason: verify recomputes the expected
            // amount from what the order says it was for, never from what the client
            // sends back afterwards.
            notes.put("credits", credits);
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Project order created: user={} order={} plan={} credits={} amountPaise={}",
                    userId, orderId, pricedAs, credits, amountPaise);
            // Open the audit row here, while the buyer's request is still on the thread —
            // it is the only moment we can see their IP, browser and originating page.
            paymentAttemptService.open(orderId, PaymentFlow.PROJECT, userId, amountPaise,
                    pricingService.currency(), description, pricedAs.name());

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

    /** True for a bundle, false for a single project; anything else is refused. */
    private boolean requireKnownQuantity(int credits) {
        if (credits == 1) return false;
        if (credits == PricingService.BUNDLE_CREDITS) return true;
        throw new IllegalArgumentException(
                "Projects are bought one at a time or " + PricingService.BUNDLE_CREDITS
                + " at a time.");
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
        int credits;
        try {
            Order order = razorpayClient.orders.fetch(req.getOrderId());
            amountPaise = ((Number) order.get("amount")).intValue();
            JSONObject notes = order.get("notes");
            String purpose = notes != null ? notes.optString("purpose", "") : "";
            String orderUserId = notes != null ? notes.optString("userId", "") : "";
            String planName = notes != null ? notes.optString("plan", "") : "";
            pricedAs = parsePlan(planName);
            // Orders placed before bundles existed carry no quantity and bought one.
            credits = notes != null ? notes.optInt("credits", 1) : 1;

            // The amount must still be what that tier charges for that quantity. Without
            // it, an order whose notes were somehow crafted could claim three projects
            // for a rupee.
            if (!ORDER_PURPOSE.equals(purpose) || !userId.equals(orderUserId) || pricedAs == null
                    || amountPaise != expectedAmount(pricedAs, credits)) {
                log.warn("Project order mismatch: user={} order={} amount={} purpose={} orderUser={} plan={} credits={}",
                        userId, req.getOrderId(), amountPaise, purpose, orderUserId, planName, credits);
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during project verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        boolean bundle = credits == PricingService.BUNDLE_CREDITS;
        claimPayment(req, userId, pricedAs, amountPaise,
                bundle ? ProjectPurchase.Purpose.BUNDLE : ProjectPurchase.Purpose.PROJECT,
                credits, null);
        if (bundle) {
            projectCreditService.creditPurchasedBundle(userId);
        } else {
            projectCreditService.creditPurchasedProject(userId, ProjectCredit.Source.PURCHASE);
        }
        log.info("Project(s) bought with money: user={} plan={} credits={} amountPaise={}",
                userId, pricedAs, credits, amountPaise);
        billingEmailService.sendProjectPurchased(userId, amountPaise, pricingService.projectValidDays());
    }

    /**
     * What an order for {@code credits} projects at {@code pricedAs} must have charged.
     *
     * Deliberately re-derived rather than trusted, and deliberately refusing any quantity
     * that is not one of the two we sell: an unrecognised quantity has no price, so it can
     * never match, so it can never be redeemed.
     */
    private int expectedAmount(Plan pricedAs, int credits) {
        if (credits == 1) {
            return pricedAs.extraProjectPriceWithTaxInPaise();
        }
        if (credits == PricingService.BUNDLE_CREDITS) {
            return pricedAs.extraProjectPriceWithTaxInPaise() * PricingService.BUNDLE_PAID_FOR;
        }
        return Integer.MIN_VALUE;
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
        boolean closed = projectCreditService.requireReopenable(userId, projectId).isClosed();
        int amountPaise = pricingService.reopenPricePaise(closed);
        try {
            JSONObject req = new JSONObject();
            req.put("amount", amountPaise);
            req.put("currency", pricingService.currency());
            req.put("receipt", "reopen_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("purpose", REOPEN_PURPOSE);
            notes.put("projectId", projectId);
            // Which of the two reopens this is travels on the order, for the same reason
            // the tier does on a project purchase: a closed project reopened by some other
            // route between paying and returning would otherwise be re-priced at the ₹9
            // lapsed rate on the way back, and a correctly-paid ₹99 would fail to verify.
            notes.put("closed", closed);
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Reopen order created: user={} order={} project={} amountPaise={}",
                    userId, orderId, projectId, amountPaise);
            paymentAttemptService.open(orderId, PaymentFlow.REOPEN, userId, amountPaise,
                    pricingService.currency(), "Reopen project " + projectId, null);

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
            // Orders placed before closure existed carry no flag and were all lapsed windows.
            boolean closed = notes != null && notes.optBoolean("closed", false);

            if (!REOPEN_PURPOSE.equals(purpose) || !userId.equals(orderUserId)
                    || projectId.isBlank()
                    || amountPaise != pricingService.reopenPricePaise(closed)) {
                log.warn("Reopen order mismatch: user={} order={} amount={} purpose={} orderUser={} project={} closed={}",
                        userId, req.getOrderId(), amountPaise, purpose, orderUserId, projectId, closed);
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during reopen verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        claimPayment(req, userId, null, amountPaise,
                ProjectPurchase.Purpose.REOPEN, 0, projectId);
        ProjectReopenResponse reopened = projectCreditService.creditReopen(userId, projectId, amountPaise);
        billingEmailService.sendProjectReopened(userId, 0, amountPaise, reopened.getDaysAdded());
        return reopened;
    }

    // ── Render top-up: one more AI image on a project that spent its included one ──

    /**
     * Create the Razorpay order for an extra AI render.
     *
     * Refused up front when the project still has a render left, for the same reason a
     * reopen is: selling somebody a thing they already have is the failure this check
     * exists to prevent, and discovering it after the payment sheet is too late.
     */
    public ProjectOrderResponse createRenderOrder(String userId, String projectId) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("Online payment is not configured.");
        }
        projectCreditService.requireRenderTopUp(userId, projectId);
        int amountPaise = pricingService.renderTopUpPricePaise();
        try {
            JSONObject req = new JSONObject();
            req.put("amount", amountPaise);
            req.put("currency", pricingService.currency());
            req.put("receipt", "render_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("purpose", RENDER_PURPOSE);
            notes.put("projectId", projectId);
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Render order created: user={} order={} project={} amountPaise={}",
                    userId, orderId, projectId, amountPaise);
            paymentAttemptService.open(orderId, PaymentFlow.RENDER, userId, amountPaise,
                    pricingService.currency(), "1 AI image for project " + projectId, null);

            return ProjectOrderResponse.builder()
                    .orderId(orderId)
                    .pricingPlan(pricingService.pricingPlanFor(userId).name())
                    .amount(amountPaise)
                    .currency(pricingService.currency())
                    .razorpayKeyId(keyId)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay render order creation failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
    }

    /** Verify the Checkout signature and add one render to the project the ORDER named. */
    @Transactional
    public void verifyAndCreditRender(String userId, VerifyProjectPurchaseRequest req) {
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

            if (!RENDER_PURPOSE.equals(purpose) || !userId.equals(orderUserId)
                    || projectId.isBlank()
                    || amountPaise != pricingService.renderTopUpPricePaise()) {
                log.warn("Render order mismatch: user={} order={} amount={} purpose={} orderUser={} project={}",
                        userId, req.getOrderId(), amountPaise, purpose, orderUserId, projectId);
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during render verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        claimPayment(req, userId, null, amountPaise,
                ProjectPurchase.Purpose.RENDER, 0, projectId);
        projectCreditService.creditExtraRender(userId, projectId);
        log.info("Extra AI render bought: user={} project={} amountPaise={}",
                userId, projectId, amountPaise);
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
                              Plan pricedAs, int amountPaise,
                              ProjectPurchase.Purpose purpose, int credits, String projectId) {
        if (purchaseRepository.existsByPaymentId(req.getPaymentId())) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
        try {
            purchaseRepository.saveAndFlush(ProjectPurchase.of(
                    req.getPaymentId(), req.getOrderId(), userId, pricedAs, amountPaise,
                    purpose, credits, projectId));
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
